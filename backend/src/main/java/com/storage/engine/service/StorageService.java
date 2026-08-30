package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AddStorageEngineRequest;
import com.storage.engine.model.DataItem;
import com.storage.engine.service.adapter.StorageAdapter;
import com.storage.engine.service.adapter.StorageAdapterFactory;
import com.storage.engine.service.adapter.StorageUtils;
import com.storage.engine.utils.ScriptExecutionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class StorageService {

    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    private static final String DEFAULT_EXTERN_SCHEMA_PREFIX = "data.extern";
    private static final String EXTERN_LOGICAL_PREFIX = "/extern";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int EXTERNAL_SIZE_FLUSH_BATCH = 100;
    private static final long EXTERNAL_SIZE_FLUSH_BYTES = 512L * 1024L * 1024L * 1024L; // 512GB
    private static final int STRUCTURED_SAMPLE_ROW_COUNT = 100;
    private static final long FULL_ROW_BYTES = 1024L * 1024L;
    private static final String DEFAULT_FILESYSTEM_STRUCT = "FileTree";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final Set<String> IMAGE_FILE_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
    ));

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private StorageAdapterFactory adapterFactory;

    @Autowired
    private AccessService accessService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private MetadataExtractionSchedulerService metadataExtractionSchedulerService;

    @Autowired
    private ScriptExecutionUtils scriptExecutionUtils;

    @Value("${external.source.ssh-directory-cache.max-entries:32}")
    private int sshDirectoryCacheMaxEntries;

    @Value("${external.source.ssh-directory-cache.ttl-ms:900000}")
    private long sshDirectoryCacheTtlMs;

    private final Object metaIdAllocationLock = new Object();
    private final Object sshDirectoryFileCacheLock = new Object();
    private final LinkedHashMap<SshDirectoryCacheKey, SshDirectoryFileCacheEntry> sshDirectoryFileCache =
            new LinkedHashMap<SshDirectoryCacheKey, SshDirectoryFileCacheEntry>(16, 0.75F, true);
    private long nextMetaIdCandidate = -1L;

    public synchronized Map<String, Object> addExternalStorageEngine(AddStorageEngineRequest request) {
        AddSourceContext context = validateAndBuildContext(request);
        String sql = buildAddStorageEngineSql(context);

        logger.info("[ExternalSource] ADD STORAGEENGINE SQL: {}", sql);

        executeAddStorageEngineSql(context, sql);

        // 同步注册数据源到元数据表，确保前端能立即看到
        logger.info("[ExternalSource] Registering data source synchronously: schemaPrefix={}", context.schemaPrefix);
        try {
            dataSourceService.registerExternalDataSource(
                    context.ip,
                    context.port,
                    context.sourceType,
                    context.schemaPrefix,
                    "",
                    0L);
            logger.info("[ExternalSource] Data source registered successfully: schemaPrefix={}", context.schemaPrefix);
        } catch (Exception e) {
            logger.error("[ExternalSource] Failed to register data source: schemaPrefix={}", context.schemaPrefix, e);
            throw new RuntimeException("数据源注册失败: " + e.getMessage(), e);
        }

        if (isFilesystemExternalSource(context)) {
            triggerExternalSchemaMetadataSyncAsync(context);
        } else {
            triggerExternalMetadataSyncAsync(context);
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sql", sql);
        payload.put("sourceType", context.sourceType);
        payload.put("schemaPrefix", context.schemaPrefix);
        payload.put("mappedDataType", context.mappedDataType);
        payload.put("syncStatus", "REGISTERED");
        payload.put("message", "添加数据源成功，数据源已注册，后台将持续进行解析");
        return payload;
    }

    private void triggerExternalMetadataSyncAsync(AddSourceContext context) {
        final AddSourceContext asyncContext = copyContext(context);
        CompletableFuture.runAsync(() -> {
            try {
                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "running",
                        "STARTED",
                        "外部数据源解析任务已启动: schemaPrefix=" + safe(asyncContext.schemaPrefix),
                        "External-Source");

                ExternalMetaSyncResult syncResult = syncExternalMetadata(asyncContext);
                persistImportedTrees(asyncContext, syncResult);
                logger.info(
                    "[ExternalSource] Async metadata sync finished. sourceType={}, schemaPrefix={}, discovered={}, imported={}, skipped={}, replaced={}",
                    asyncContext.sourceType,
                    asyncContext.schemaPrefix,
                    syncResult.discoveredCount,
                    syncResult.importedCount,
                    syncResult.skippedCount,
                    syncResult.replacedCount);

                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "success",
                        "DONE",
                        "外部数据源解析完成: schemaPrefix=" + safe(asyncContext.schemaPrefix)
                                + ", imported=" + syncResult.importedCount
                                + ", skipped=" + syncResult.skippedCount,
                        "External-Source");
            } catch (Exception ex) {
                logger.error(
                    "[ExternalSource] Async metadata sync failed. sourceType={}, schemaPrefix={}",
                    asyncContext.sourceType,
                    asyncContext.schemaPrefix,
                    ex);

                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "warn",
                        "FAILED",
                        "外部数据源解析失败: schemaPrefix=" + safe(asyncContext.schemaPrefix)
                                + ", reason=" + safe(ex.getMessage()),
                        "External-Source");
            }
        });
    }

    private void triggerExternalSchemaMetadataSyncAsync(AddSourceContext context) {
        final AddSourceContext asyncContext = copyContext(context);
        CompletableFuture.runAsync(() -> {
            try {
                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "running",
                        "STARTED",
                        "Filesystem schema extraction started: schemaPrefix=" + safe(asyncContext.schemaPrefix),
                        "External-Source");

                ExternalMetaSyncResult syncResult = syncExternalSchemaMetadata(asyncContext);
                persistImportedTrees(asyncContext, syncResult);
                logger.info(
                    "[ExternalSource] Async schema metadata sync finished. schemaPrefix={}, discovered={}, imported={}, skipped={}, replaced={}",
                    asyncContext.schemaPrefix,
                    syncResult.discoveredCount,
                    syncResult.importedCount,
                    syncResult.skippedCount,
                    syncResult.replacedCount);

                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "success",
                        "DONE",
                        "Filesystem schema extraction completed: schemaPrefix=" + safe(asyncContext.schemaPrefix)
                                + ", imported=" + syncResult.importedCount
                                + ", skipped=" + syncResult.skippedCount,
                        "External-Source");
            } catch (Exception ex) {
                logger.error("[ExternalSource] Async schema metadata sync failed. schemaPrefix={}", asyncContext.schemaPrefix, ex);
                metadataExtractionSchedulerService.publishExternalSourceEvent(
                        "warn",
                        "FAILED",
                        "Filesystem schema extraction failed: schemaPrefix=" + safe(asyncContext.schemaPrefix)
                                + ", reason=" + safe(ex.getMessage()),
                        "External-Source");
            }
        });
    }

    private AddSourceContext copyContext(AddSourceContext src) {
        AddSourceContext copy = new AddSourceContext();
        if (src == null) {
            return copy;
        }
        copy.sourceType = src.sourceType;
        copy.ip = src.ip;
        copy.port = src.port;
        copy.username = src.username;
        copy.password = src.password;
        copy.dummyDir = src.dummyDir;
        copy.iginxPort = src.iginxPort;
        copy.schemaPrefix = src.schemaPrefix;
        copy.logicalSourceKey = src.logicalSourceKey;
        copy.mappedDataType = src.mappedDataType;
        copy.sizeCalculationStrategy = src.sizeCalculationStrategy;
        copy.sshUsername = src.sshUsername;
        copy.sshPassword = src.sshPassword;
        copy.sshPort = src.sshPort;
        copy.sshFileSizeCache = src.sshFileSizeCache;
        return copy;
    }

    /**
     * Store a file to IGinX using the adapter pattern.
        * Logical path is treated as a folder and can hold multiple files.
     */
    public synchronized DataItem storeData(MultipartFile file, String logicalPath, String dataType) throws Exception {
        // Normalize logical path
        logicalPath = normalizePath(logicalPath);
        dataType = canonicalDataType(dataType);

        // Reject root path
        if ("/".equals(logicalPath)) {
            throw new IllegalArgumentException("不能使用根路径 '/' 作为存储路径，请指定具体的逻辑路径（如 /project/data）。");
        }

        // Get the appropriate adapter
        StorageAdapter adapter = adapterFactory.getAdapter(dataType);

        String fileName = normalizeFileName(file.getOriginalFilename());
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // Allow duplicate logical path, but prevent duplicate file names under the same logical folder.
        DataItem existingSameFile = accessService.getMetaByPathAndFileName(logicalPath, fileName);
        if (existingSameFile != null) {
            throw new IllegalArgumentException(
                    "逻辑路径 '" + logicalPath + "' 下已存在同名文件 '" + fileName + "'。请重命名后重试。");
        }

        long fileSize = file.getSize();
        String fileFormat = getFileExtension(fileName);
        String createTime = LocalDateTime.now().format(TIME_FORMATTER);

        // Generate metadata ID
        long metaId = allocateMetaId();

        // Convert logical folder path to a file-scoped IGinX path.
        String folderPath = StorageUtils.toIginxDataPath(logicalPath);
        String iginxPath = StorageUtils.toFileLeafPath(folderPath, fileName);
        String contentPath = buildContentPath(iginxPath, dataType);
        String knowledgeExtractStatus = initialKnowledgeExtractStatus(dataType, fileName, fileFormat);

        // Store metadata
        ensureDirectoryAssetsInMeta(logicalPath, createTime, accessService.getAllMeta());
        iginxDao.insertMeta(metaId, logicalPath, dataType, fileName, contentPath, fileSize, fileFormat, createTime, knowledgeExtractStatus);

        // Delegate to adapter for actual data storage
        adapter.store(file, iginxPath);

        DataItem item = new DataItem();
        item.setId((int) metaId);
        item.setLogicalPath(logicalPath);
        item.setDataType(dataType);
        item.setFileName(fileName);
        item.setFileSize(fileSize);
        item.setFileFormat(fileFormat);
        item.setCreateTime(createTime);
        item.setIsValid(true);
        item.setKnowledgeExtractStatus(knowledgeExtractStatus);
        if (STATUS_SKIPPED.equals(knowledgeExtractStatus)) {
            item.setSemanticKeywords("[]");
        }
        item.setContentPath(contentPath);

        dataSourceService.increaseDefaultDataSourceSize(fileSize);
        publishInitialKnowledgeExtractEvent("Storage-Service", logicalPath, fileName, dataType, fileFormat, knowledgeExtractStatus);
        metadataExtractionSchedulerService.persistMetadataTreeAsync(item);
        return item;
    }

    private void persistImportedTrees(AddSourceContext context, ExternalMetaSyncResult syncResult) {
        if (syncResult == null || syncResult.importedItems.isEmpty()) {
            return;
        }
        String sourceKey = context == null ? "" : safe(context.logicalSourceKey);
        String sourcePath = normalizePath(EXTERN_LOGICAL_PREFIX + "/" + sourceKey);
        metadataExtractionSchedulerService.persistMetadataTreeForSourceAsync(sourcePath, syncResult.importedItems);
    }

    private long allocateMetaId() {
        synchronized (metaIdAllocationLock) {
            long databaseNextId = iginxDao.getMaxMetaId() + 1;
            long nextId = nextMetaIdCandidate < 0L
                    ? databaseNextId
                    : Math.max(nextMetaIdCandidate, databaseNextId);
            nextMetaIdCandidate = nextId + 1;
            return nextId;
        }
    }

    private String buildContentPath(String iginxDataPath, String dataType) {
        String normalizedPath = StorageUtils.normalizeEscapedPath(safe(iginxDataPath));
        if (normalizedPath.isEmpty()) {
            return "";
        }

        String prefix = IGinxConstants.DATA_PATH_PREFIX + ".";
        String relativePath = normalizedPath;
        if (normalizedPath.startsWith(prefix)) {
            relativePath = normalizedPath.substring(prefix.length());
        } else if (IGinxConstants.DATA_PATH_PREFIX.equals(normalizedPath)) {
            relativePath = "";
        }

        if (relativePath.isEmpty()) {
            return "";
        }

        if (isStructuredDataType(dataType) && !relativePath.endsWith(".*")) {
            relativePath = relativePath + ".*";
        }

        return relativePath;
    }

    private boolean isStructuredDataType(String dataType) {
        String type = safe(dataType).toLowerCase(Locale.ROOT);
        return IGinxConstants.TYPE_RELATIONAL.equals(type)
                || IGinxConstants.TYPE_TIMESERIES.equals(type)
                || IGinxConstants.TYPE_KEYVALUE.equals(type)
                || IGinxConstants.TYPE_DOCUMENT.equals(type);
    }

    private int ensureDirectoryAssetsInMeta(String leafParentLogicalPath,
                                            String createTime,
                                            List<DataItem> existingMeta) {
        List<String> directories = buildDirectoryAssetPaths(leafParentLogicalPath);
        if (directories.isEmpty()) {
            return 0;
        }
        List<DataItem> metaIndex = existingMeta == null ? new ArrayList<DataItem>() : existingMeta;
        int inserted = 0;
        for (String dirPath : directories) {
            String dirName = getFileNameFromLogicalPath(dirPath);
            String parentPath = parentLogicalPath(dirPath);
            DataItem existing = findDirectoryMetaByPathAndFile(metaIndex, parentPath, dirName);
            DataItem legacy = findDirectoryMetaByPathAndFile(metaIndex, dirPath, dirName);
            if (legacy != null && legacy.getId() != null) {
                iginxDao.deleteMeta(legacy.getId().longValue());
                legacy.setIsValid(false);
            }
            if (existing != null) {
                continue;
            }
            long metaId = allocateMetaId();
            iginxDao.insertMeta(
                    metaId,
                    parentPath,
                    IGinxConstants.TYPE_DIRECTORY,
                    dirName,
                    "",
                    0L,
                    "",
                    createTime,
                    STATUS_PENDING);

            DataItem added = new DataItem();
            added.setId((int) metaId);
            added.setLogicalPath(parentPath);
            added.setFileName(dirName);
            added.setDataType(IGinxConstants.TYPE_DIRECTORY);
            added.setContentPath("");
            added.setFileSize(0L);
            added.setIsValid(true);
            added.setKnowledgeExtractStatus(STATUS_PENDING);
            metaIndex.add(added);
            inserted++;
        }
        return inserted;
    }

    private List<String> buildDirectoryAssetPaths(String leafParentLogicalPath) {
        List<String> out = new ArrayList<String>();
        String normalized = normalizePath(leafParentLogicalPath);
        if ("/".equals(normalized)) {
            return out;
        }
        String[] parts = normalized.substring(1).split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String seg = safe(part);
            if (seg.isEmpty()) {
                continue;
            }
            current.append('/').append(seg);
            out.add(current.toString());
        }
        return out;
    }

    private String parentLogicalPath(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return normalized.substring(0, idx);
    }

    private DataItem findDirectoryMetaByPathAndFile(List<DataItem> items, String logicalPath, String fileName) {
        if (items == null) {
            return null;
        }
        String targetPath = normalizePath(logicalPath);
        String targetFile = normalizeFileName(fileName);
        DataItem picked = null;
        for (DataItem item : items) {
            if (item == null || Boolean.FALSE.equals(item.getIsValid())) {
                continue;
            }
            if (!IGinxConstants.TYPE_DIRECTORY.equals(safe(item.getDataType()).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!targetPath.equals(normalizePath(item.getLogicalPath()))) {
                continue;
            }
            if (!targetFile.equals(normalizeFileName(item.getFileName()))) {
                continue;
            }
            if (picked == null || compareItemId(item, picked) > 0) {
                picked = item;
            }
        }
        return picked;
    }

    // ==================== Utility Methods ====================

    private ExternalMetaSyncResult syncExternalMetadata(AddSourceContext context) {
        List<String> columns = listExternalColumns(context);
        Set<String> assetPaths = deriveAssetPaths(columns, context);

        ExternalMetaSyncResult result = new ExternalMetaSyncResult();
        result.discoveredCount = assetPaths.size();

        if (assetPaths.isEmpty()) {
            return result;
        }

        String createTime = LocalDateTime.now().format(TIME_FORMATTER);
        List<DataItem> existingMeta = accessService.getAllMeta();

        long pendingSizeDelta = 0L;
        int pendingFlushFiles = 0;

        for (String assetPath : assetPaths) {
            String logicalPath = toExternalLogicalPath(assetPath, context);
            String fileName = deriveExternalFileName(assetPath, context, logicalPath);
            result.importedCount += ensureDirectoryAssetsInMeta(logicalPath, createTime, existingMeta);
            DataItem existing = findMetaByPathAndFile(existingMeta, logicalPath, fileName);
            if (existing != null) {
                result.skippedCount++;
                publishExternalSyncEvent("info", "SKIPPED", logicalPath, fileName, 0L, "已存在同名元数据，跳过");
                continue;
            }

            DataItem legacy = findLegacyExternalStructuredMeta(existingMeta, logicalPath, fileName, context);
            if (legacy != null && legacy.getId() != null) {
                iginxDao.deleteMeta(legacy.getId().longValue());
                existingMeta.remove(legacy);
                result.replacedCount++;
            }

            String fileFormat = getFileExtension(fileName);
            String inferredDataType = inferExternalDataType(context, fileName, fileFormat);
            String contentPath = buildContentPath(assetPath, inferredDataType);
            long estimatedFileSize = estimateExternalAssetSize(assetPath, context);
            long metaId = allocateMetaId();
            String knowledgeExtractStatus = initialKnowledgeExtractStatus(inferredDataType, fileName, fileFormat);

            iginxDao.insertMeta(
                    metaId,
                    logicalPath,
                    inferredDataType,
                    fileName,
                    contentPath,
                    estimatedFileSize,
                    fileFormat,
                    createTime,
                    knowledgeExtractStatus);

            result.importedCount++;
            result.totalEstimatedSize += Math.max(0L, estimatedFileSize);
            result.importedLogicalPaths.add(normalizePath(logicalPath + "/" + fileName));

            DataItem added = new DataItem();
            added.setId((int) metaId);
            added.setLogicalPath(logicalPath);
            added.setFileName(fileName);
            added.setDataType(inferredDataType);
            added.setContentPath(contentPath);
            added.setFileSize(estimatedFileSize);
            added.setKnowledgeExtractStatus(knowledgeExtractStatus);
            if (STATUS_SKIPPED.equals(knowledgeExtractStatus)) {
                added.setSemanticKeywords("[]");
            }
            existingMeta.add(added);
            result.importedItems.add(added);
            publishInitialKnowledgeExtractEvent("External-Source", logicalPath, fileName, inferredDataType, fileFormat, knowledgeExtractStatus);

            pendingSizeDelta += Math.max(0L, estimatedFileSize);
            pendingFlushFiles++;
            if (pendingFlushFiles >= EXTERNAL_SIZE_FLUSH_BATCH || pendingSizeDelta >= EXTERNAL_SIZE_FLUSH_BYTES) {
                dataSourceService.increaseExternalDataSourceSize(context.schemaPrefix, pendingSizeDelta);
                publishExternalSyncEvent("info", "SIZE_FLUSH", logicalPath, fileName, pendingSizeDelta,
                        "已累计更新数据源大小，批次文件数=" + pendingFlushFiles + ", 批次字节数=" + pendingSizeDelta);
                pendingSizeDelta = 0L;
                pendingFlushFiles = 0;
            }

            publishExternalSyncEvent("running", "IMPORTED", logicalPath, fileName, estimatedFileSize, "已完成元数据导入");
        }

        if (pendingSizeDelta > 0L) {
            dataSourceService.increaseExternalDataSourceSize(context.schemaPrefix, pendingSizeDelta);
            publishExternalSyncEvent("info", "SIZE_FLUSH", "", "", pendingSizeDelta,
                    "已提交尾批数据源大小更新，批次文件数=" + pendingFlushFiles);
        }

        return result;
    }

    private ExternalMetaSyncResult syncExternalSchemaMetadata(AddSourceContext context) {
        String showColumnsSql = buildShowColumnsSql(context.schemaPrefix);
        logger.info("[ExternalSource] Filesystem schema show columns SQL: {}", showColumnsSql);
        SessionExecuteSqlResult showColumnsResult = iginxDao.executeSql(showColumnsSql);
        logger.info("[ExternalSource] Filesystem schema show columns raw result. paths={}, rows={}, firstRowSize={}, dataTypes={}",
                showColumnsResult == null || showColumnsResult.getPaths() == null ? 0 : showColumnsResult.getPaths().size(),
                showColumnsResult == null || showColumnsResult.getValues() == null ? 0 : showColumnsResult.getValues().size(),
                showColumnsResult == null || showColumnsResult.getValues() == null || showColumnsResult.getValues().isEmpty()
                        || showColumnsResult.getValues().get(0) == null ? 0 : showColumnsResult.getValues().get(0).size(),
                showColumnsResult == null || showColumnsResult.getDataTypeList() == null ? 0 : showColumnsResult.getDataTypeList().size());
        List<ColumnSchema> columnSchemas = parseShowColumnsSchemas(showColumnsResult);
        Map<String, FilesystemSchemaAsset> assets = groupFilesystemSchemaAssets(columnSchemas, context);
        logger.info("[ExternalSource] Filesystem schema columns parsed. schemaPrefix={}, columnCount={}, assetCount={}, sampleColumns={}",
                context.schemaPrefix,
                columnSchemas.size(),
                assets.size(),
                summarizeSchemaColumns(columnSchemas));

        ExternalMetaSyncResult result = new ExternalMetaSyncResult();
        result.discoveredCount = assets.size();
        if (assets.isEmpty()) {
            return result;
        }

        String createTime = LocalDateTime.now().format(TIME_FORMATTER);
        List<DataItem> existingMeta = accessService.getAllMeta();
        long pendingSizeDelta = 0L;
        int pendingFlushFiles = 0;

        for (FilesystemSchemaAsset asset : assets.values()) {
            String assetPath = asset.assetPath;
            String logicalPath = toFilesystemFolderLogicalPath(assetPath, context);
            String fileName = deriveExternalFileName(assetPath, context, logicalPath);
            result.importedCount += ensureDirectoryAssetsInMeta(logicalPath, createTime, existingMeta);

            int removedColumnMetas = deleteLegacySchemaColumnMetas(existingMeta, logicalPath, fileName);
            if (removedColumnMetas > 0) {
                result.replacedCount += removedColumnMetas;
                publishExternalSyncEvent("info", "REPLACED", logicalPath, fileName, 0L,
                        "removed legacy column-level metadata count=" + removedColumnMetas);
            }

            String fileFormat = getFileExtension(fileName);
            String inferredDataType = inferSchemaFilesystemDataType(fileName, fileFormat, asset.columns);
            String contentPath = buildContentPath(assetPath, inferredDataType);

            DataItem existing = findMetaByPathAndFile(existingMeta, logicalPath, fileName);
            if (existing != null) {
                if (!shouldRefreshSchemaMeta(existing, inferredDataType, contentPath)) {
                    result.skippedCount++;
                    publishExternalSyncEvent("info", "SKIPPED", logicalPath, fileName, 0L, "metadata already exists");
                    continue;
                }
                iginxDao.deleteMeta(existing.getId().longValue());
                existingMeta.remove(existing);
                result.replacedCount++;
                publishExternalSyncEvent("info", "REPLACED", logicalPath, fileName, 0L,
                        "refreshed stale schema metadata");
            }

            long estimatedFileSize = estimateSchemaFilesystemAssetSize(assetPath, context, inferredDataType, asset.columns);
            long metaId = allocateMetaId();
            String knowledgeExtractStatus = initialKnowledgeExtractStatus(inferredDataType, fileName, fileFormat);

            iginxDao.insertMeta(
                    metaId,
                    logicalPath,
                    inferredDataType,
                    fileName,
                    contentPath,
                    estimatedFileSize,
                    fileFormat,
                    createTime,
                    knowledgeExtractStatus);

            result.importedCount++;
            result.totalEstimatedSize += Math.max(0L, estimatedFileSize);
            result.importedLogicalPaths.add(normalizePath(logicalPath + "/" + fileName));
            DataItem added = new DataItem();
            added.setId((int) metaId);
            added.setLogicalPath(logicalPath);
            added.setFileName(fileName);
            added.setDataType(inferredDataType);
            added.setContentPath(contentPath);
            added.setFileSize(estimatedFileSize);
            added.setKnowledgeExtractStatus(knowledgeExtractStatus);
            if (STATUS_SKIPPED.equals(knowledgeExtractStatus)) {
                added.setSemanticKeywords("[]");
            }
            existingMeta.add(added);
            result.importedItems.add(added);
            publishInitialKnowledgeExtractEvent("External-Source", logicalPath, fileName, inferredDataType, fileFormat, knowledgeExtractStatus);

            pendingSizeDelta += Math.max(0L, estimatedFileSize);
            pendingFlushFiles++;
            if (pendingFlushFiles >= EXTERNAL_SIZE_FLUSH_BATCH || pendingSizeDelta >= EXTERNAL_SIZE_FLUSH_BYTES) {
                dataSourceService.increaseExternalDataSourceSize(context.schemaPrefix, pendingSizeDelta);
                pendingSizeDelta = 0L;
                pendingFlushFiles = 0;
            }

            publishExternalSyncEvent("running", "IMPORTED", logicalPath, fileName, estimatedFileSize,
                    "schema metadata imported");
        }

        if (pendingSizeDelta > 0L) {
            dataSourceService.increaseExternalDataSourceSize(context.schemaPrefix, pendingSizeDelta);
        }

        return result;
    }

    private boolean shouldRefreshSchemaMeta(DataItem existing, String expectedDataType, String expectedContentPath) {
        if (existing == null) {
            return false;
        }
        String actualType = safe(existing.getDataType()).toLowerCase(Locale.ROOT);
        String expectedType = safe(expectedDataType).toLowerCase(Locale.ROOT);
        if (!actualType.equals(expectedType)) {
            return true;
        }
        String actualContentPath = StorageUtils.normalizeEscapedPath(safe(existing.getContentPath()));
        String normalizedExpectedContentPath = StorageUtils.normalizeEscapedPath(safe(expectedContentPath));
        if (!actualContentPath.equals(normalizedExpectedContentPath)) {
            return true;
        }
        if (!isSchemaTabularDataType(expectedDataType)) {
            return true;
        }
        Long size = existing.getFileSize();
        return size == null || size.longValue() <= 0L;
    }

    private List<String> listExternalColumns(AddSourceContext context) {
        SessionExecuteSqlResult result = iginxDao.executeSql(buildShowColumnsSql(context.schemaPrefix));

        LinkedHashSet<String> deduped = new LinkedHashSet<String>();
        String expectedPrefix = context.schemaPrefix + ".";
        for (ColumnSchema columnSchema : parseShowColumnsSchemas(result)) {
            String path = columnSchema.path;
            if (path.startsWith(expectedPrefix)) {
                deduped.add(path.trim());
            }
        }
        return new ArrayList<String>(deduped);
    }

    private Map<String, FilesystemSchemaAsset> groupFilesystemSchemaAssets(List<ColumnSchema> schemas, AddSourceContext context) {
        LinkedHashMap<String, FilesystemSchemaAsset> assets = new LinkedHashMap<String, FilesystemSchemaAsset>();
        if (schemas == null || context == null) {
            return assets;
        }
        String expectedPrefix = context.schemaPrefix + ".";
        for (ColumnSchema schema : schemas) {
            if (schema == null || schema.path.isEmpty() || !schema.path.startsWith(expectedPrefix)) {
                continue;
            }
            String assetPath = deriveFilesystemFileAssetPath(schema.path, context);
            if (assetPath.isEmpty()) {
                continue;
            }
            FilesystemSchemaAsset asset = assets.get(assetPath);
            if (asset == null) {
                asset = new FilesystemSchemaAsset(assetPath);
                assets.put(assetPath, asset);
            }
            asset.columns.add(schema);
        }
        return assets;
    }

    private String deriveFilesystemFileAssetPath(String columnPath, AddSourceContext context) {
        List<String> segments = splitUnescapedSegments(columnPath);
        if (segments.isEmpty()) {
            return "";
        }

        int startIndex = splitUnescapedSegments(context.schemaPrefix).size();
        for (int i = Math.max(0, startIndex); i < segments.size(); i++) {
            String segment = safe(segments.get(i));
            if (!isFilesystemFileSegment(segment)) {
                continue;
            }
            List<String> assetSegments = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                assetSegments.add(segments.get(j));
            }
            return joinEscapedPathSegments(assetSegments);
        }
        return "";
    }

    private boolean isFilesystemFileSegment(String segment) {
        String ext = getFileExtension(segment);
        if (ext.isEmpty()) {
            return false;
        }
        return true;
    }

    private String joinEscapedPathSegments(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        if (segments == null) {
            return "";
        }
        for (String segment : segments) {
            String safeSegment = safe(segment);
            if (safeSegment.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(escapePathSegment(safeSegment));
        }
        return sb.toString();
    }

    private String escapePathSegment(String segment) {
        return safe(segment).replace("\\", "\\\\").replace(".", "\\.");
    }

    private String inferSchemaFilesystemDataType(String fileName, String fileFormat, List<ColumnSchema> columns) {
        String ext = safe(fileFormat).toLowerCase(Locale.ROOT);
        String lowerName = safe(fileName).toLowerCase(Locale.ROOT);
        if ("csv".equals(ext)) {
            if (looksLikeTimeseriesName(lowerName) || hasTimeLikeColumn(columns)) {
                return IGinxConstants.TYPE_TIMESERIES;
            }
            return IGinxConstants.TYPE_RELATIONAL;
        }
        return IGinxConstants.TYPE_FILE;
    }

    private boolean hasTimeLikeColumn(List<ColumnSchema> columns) {
        if (columns == null) {
            return false;
        }
        for (ColumnSchema column : columns) {
            String leaf = getLastPathSegment(column == null ? "" : column.path).toLowerCase(Locale.ROOT);
            if ("time".equals(leaf)
                    || "timestamp".equals(leaf)
                    || "date".equals(leaf)
                    || "datetime".equals(leaf)
                    || leaf.endsWith("_time")
                    || leaf.endsWith("_timestamp")) {
                return true;
            }
        }
        return false;
    }

    private int deleteLegacySchemaColumnMetas(List<DataItem> existingMeta, String logicalPath, String schemaFileName) {
        if (existingMeta == null || schemaFileName == null || schemaFileName.trim().isEmpty()) {
            return 0;
        }
        String legacyColumnFolder = normalizePath(logicalPath + "/" + normalizeFileName(schemaFileName));
        int removed = 0;
        Iterator<DataItem> iterator = existingMeta.iterator();
        while (iterator.hasNext()) {
            DataItem item = iterator.next();
            if (item == null || item.getId() == null) {
                continue;
            }
            if (!legacyColumnFolder.equals(normalizePath(item.getLogicalPath()))) {
                continue;
            }
            iginxDao.deleteMeta(item.getId().longValue());
            iterator.remove();
            removed++;
        }
        return removed;
    }

    private String getLastPathSegment(String path) {
        List<String> segments = splitUnescapedSegments(path);
        if (segments.isEmpty()) {
            return "";
        }
        return safe(segments.get(segments.size() - 1));
    }

    private long estimateSchemaFilesystemAssetSize(String assetPath,
                                                   AddSourceContext context,
                                                   String dataType,
                                                   List<ColumnSchema> columns) {
        if (context != null && "ssh".equals(context.sizeCalculationStrategy)) {
            return estimateFilesystemSizeBySSH(assetPath, context);
        }
        try {
            if (isSchemaTabularDataType(dataType)) {
                return estimateSchemaTabularAssetSize(assetPath, columns);
            }
            return estimateFilesystemExternalAssetActualSize(assetPath);
        } catch (Exception e) {
            logger.warn("[ExternalSource] Schema file size fallback to 0. assetPath={}, dataType={}", assetPath, dataType, e);
        }
        return 0L;
    }

    private boolean isSchemaTabularDataType(String dataType) {
        String type = safe(dataType).toLowerCase(Locale.ROOT);
        return IGinxConstants.TYPE_RELATIONAL.equals(type)
                || IGinxConstants.TYPE_TIMESERIES.equals(type);
    }

    private long estimateSchemaTabularAssetSize(String assetPath, List<ColumnSchema> columns) {
        List<ColumnSchema> usableColumns = new ArrayList<ColumnSchema>();
        if (columns != null) {
            for (ColumnSchema column : columns) {
                if (column != null && !safe(column.path).isEmpty()) {
                    usableColumns.add(column);
                }
            }
        }
        if (usableColumns.isEmpty()) {
            usableColumns = filterColumnsUnderAsset(assetPath, columns);
        }
        if (usableColumns.isEmpty()) {
            logger.warn("[ExternalSource] No schema columns found for tabular filesystem asset: {}", assetPath);
            return 0L;
        }

        long rowCount = resolveSchemaTabularRowCount(assetPath, usableColumns);
        if (rowCount <= 0L) {
            logger.warn("[ExternalSource] Schema tabular row count is 0. assetPath={}, columns={}",
                    assetPath, summarizeSchemaColumns(usableColumns));
            return 0L;
        }

        long bytesPerRow = 0L;
        for (ColumnSchema column : usableColumns) {
            long bytes = estimateSchemaColumnBytes(assetPath, column);
            if (Long.MAX_VALUE - bytesPerRow < bytes) {
                bytesPerRow = Long.MAX_VALUE;
                break;
            }
            bytesPerRow += Math.max(0L, bytes);
        }

        if (bytesPerRow <= 0L) {
            logger.warn("[ExternalSource] Schema tabular bytesPerRow is 0. assetPath={}, rowCount={}", assetPath, rowCount);
            return 0L;
        }
        if (bytesPerRow > Long.MAX_VALUE / rowCount) {
            return Long.MAX_VALUE;
        }
        long estimated = bytesPerRow * rowCount;
        return estimated;
    }

    private List<ColumnSchema> filterColumnsUnderAsset(String assetPath, List<ColumnSchema> columns) {
        List<ColumnSchema> out = new ArrayList<ColumnSchema>();
        if (columns == null) {
            return out;
        }
        String normalizedAsset = StorageUtils.normalizeEscapedPath(safe(assetPath));
        String prefix = normalizedAsset + ".";
        for (ColumnSchema column : columns) {
            if (column == null || column.path.isEmpty()) {
                continue;
            }
            String normalizedColumn = StorageUtils.normalizeEscapedPath(column.path);
            if (normalizedColumn.startsWith(prefix)) {
                out.add(column);
            }
        }
        return out;
    }

    private long resolveSchemaTabularRowCount(String assetPath, List<ColumnSchema> columns) {
        ColumnSchema countColumn = chooseCountColumn(columns);
        if (countColumn == null) {
            return 0L;
        }
        String leaf = getLastPathSegment(countColumn.path);
        if (leaf.isEmpty()) {
            return 0L;
        }

        List<String> fromCandidates = buildSchemaAssetFromCandidates(assetPath);
        for (String from : fromCandidates) {
            String sql = "select count(" + quoteColumnLeafForSql(leaf) + ") from " + from + ";";
            try {
                SessionExecuteSqlResult result = iginxDao.executeSql(sql);
                List<Long> counts = parseCountValues(result);
                if (!counts.isEmpty()) {
                    return counts.get(0).longValue();
                }
            } catch (Exception e) {
                logger.warn("[ExternalSource] Schema tabular row count SQL failed. assetPath={}, leaf={}, sql={}, error={}",
                        assetPath, leaf, sql, e.getMessage(), e);
            }
        }
        return 0L;
    }

    private List<String> buildSchemaAssetFromCandidates(String assetPath) {
        List<String> candidates = new ArrayList<String>();
        String safePath = safe(assetPath);
        addUnique(candidates, StorageUtils.quoteIdentifierPath(safePath));
        return candidates;
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isEmpty() || values.contains(value)) {
            return;
        }
        values.add(value);
    }

    private String quoteWholeIdentifierPath(String rawPath) {
        String value = safe(rawPath);
        if (value.isEmpty()) {
            return value;
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private String quoteColumnLeafForSql(String leaf) {
        String value = safe(leaf);
        if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return value;
        }
        return StorageUtils.quoteIdentifierSegment(value);
    }

    private ColumnSchema chooseCountColumn(List<ColumnSchema> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        for (ColumnSchema column : columns) {
            if (column != null && fixedTypeBytes(column.type) > 0L) {
                return column;
            }
        }
        for (ColumnSchema column : columns) {
            if (column != null && !column.path.isEmpty()) {
                return column;
            }
        }
        return null;
    }

    private long estimateSchemaColumnBytes(String assetPath, ColumnSchema column) {
        if (column == null) {
            return 0L;
        }
        long fixedBytes = fixedTypeBytes(column.type);
        if (fixedBytes > 0L) {
            return fixedBytes;
        }
        if ("BINARY".equalsIgnoreCase(safe(column.type))) {
            return estimateSchemaBinaryColumnAverageBytes(assetPath, column);
        }
        return 0L;
    }

    private long fixedTypeBytes(String type) {
        String normalized = safe(type).toUpperCase(Locale.ROOT);
        if ("BOOLEAN".equals(normalized)) {
            return 1L;
        }
        if ("INTEGER".equals(normalized) || "INT".equals(normalized)) {
            return 4L;
        }
        if ("LONG".equals(normalized)) {
            return 8L;
        }
        if ("FLOAT".equals(normalized)) {
            return 4L;
        }
        if ("DOUBLE".equals(normalized)) {
            return 8L;
        }
        return 0L;
    }

    private long estimateSchemaBinaryColumnAverageBytes(String assetPath, ColumnSchema column) {
        String leaf = getLastPathSegment(column == null ? "" : column.path);
        if (leaf.isEmpty()) {
            return 0L;
        }
        SessionExecuteSqlResult result = null;
        for (String from : buildSchemaAssetFromCandidates(assetPath)) {
            String sql = "select " + quoteColumnLeafForSql(leaf)
                    + " from " + from
                    + " limit " + STRUCTURED_SAMPLE_ROW_COUNT + ";";
            try {
                logger.info("[ExternalSource] Fetch schema binary sample SQL: {}", sql);
                result = iginxDao.executeSql(sql);
                if (result != null && result.getValues() != null && !result.getValues().isEmpty()) {
                    break;
                }
                logger.warn("[ExternalSource] Schema binary sample result is empty. assetPath={}, leaf={}, sql={}",
                        assetPath, leaf, sql);
            } catch (Exception e) {
                logger.warn("[ExternalSource] Schema binary sample SQL failed. assetPath={}, leaf={}, sql={}, error={}",
                        assetPath, leaf, sql, e.getMessage(), e);
            }
        }
        if (result == null || result.getValues() == null || result.getValues().isEmpty()) {
            return 0L;
        }

        long total = 0L;
        int count = 0;
        for (List<Object> row : result.getValues()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            total += estimateValueBytes(row.get(0));
            count++;
        }
        if (count <= 0) {
            return 0L;
        }
        return Math.max(1L, total / count);
    }

    private String summarizeSchemaColumns(List<ColumnSchema> columns) {
        if (columns == null || columns.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(columns.size(), 12);
        for (int i = 0; i < limit; i++) {
            ColumnSchema column = columns.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(getLastPathSegment(column == null ? "" : column.path))
                    .append(":")
                    .append(column == null ? "" : safe(column.type));
        }
        if (columns.size() > limit) {
            sb.append(", ... total=").append(columns.size());
        }
        sb.append("]");
        return sb.toString();
    }

    private List<ColumnSchema> parseShowColumnsSchemas(SessionExecuteSqlResult result) {
        List<ColumnSchema> out = new ArrayList<ColumnSchema>();
        if (result == null) {
            return out;
        }

        List<String> headers = result.getPaths();
        List<List<Object>> rows = result.getValues();
        List<?> dataTypes = result.getDataTypeList();

        int pathIdx = 0;
        int typeIdx = 1;
        if (headers != null && !headers.isEmpty()) {
            for (int i = 0; i < headers.size(); i++) {
                String header = safe(headers.get(i)).toLowerCase(Locale.ROOT);
                if ("path".equals(header) || header.endsWith("path") || header.contains(".path")) {
                    pathIdx = i;
                } else if ("type".equals(header) || header.endsWith("type") || header.contains(".type")) {
                    typeIdx = i;
                }
            }
        }

        if (rows != null) {
            for (List<Object> row : rows) {
                if (row == null || row.size() <= pathIdx) {
                    continue;
                }
                String path = valueAsString(row.get(pathIdx));
                if (path.isEmpty()) {
                    continue;
                }
                String type = row.size() > typeIdx ? valueAsString(row.get(typeIdx)) : "";
                if (type.isEmpty()) {
                    type = dataTypeNameAt(dataTypes, out.size());
                }
                out.add(new ColumnSchema(path, type));
            }
        }

        if (out.isEmpty() && headers != null) {
            for (int i = 0; i < headers.size(); i++) {
                String headerPath = headers.get(i);
                if (headerPath != null) {
                    out.add(new ColumnSchema(headerPath, dataTypeNameAt(dataTypes, i)));
                }
            }
        }

        return out;
    }

    private String dataTypeNameAt(List<?> dataTypes, int index) {
        if (dataTypes == null || index < 0 || index >= dataTypes.size()) {
            return "";
        }
        Object value = dataTypes.get(index);
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private List<ColumnSchema> listExternalColumnSchemas(String assetPath, AddSourceContext context) {
        SessionExecuteSqlResult result = iginxDao.executeSql(buildShowColumnsSql(assetPath, isFilesystemExternalSource(context)));
        List<ColumnSchema> schemas = parseShowColumnsSchemas(result);
        if (schemas.isEmpty()) {
            return schemas;
        }

        List<ColumnSchema> filtered = new ArrayList<ColumnSchema>();
        for (ColumnSchema schema : schemas) {
            if (schema == null) {
                continue;
            }
            if (isColumnUnderAsset(schema.path, assetPath)) {
                filtered.add(schema);
            }
        }

        if (!filtered.isEmpty()) {
            return filtered;
        }

        String schemaPrefix = context == null ? "" : safe(context.schemaPrefix);
        if (!schemaPrefix.isEmpty()) {
            String rootPrefix = schemaPrefix + ".";
            for (ColumnSchema schema : schemas) {
                if (schema == null) {
                    continue;
                }
                if (schema.path.startsWith(rootPrefix) && isColumnUnderAsset(schema.path, assetPath)) {
                    filtered.add(schema);
                }
            }
        }
        return filtered;
    }

    private String buildShowColumnsSql(String prefix) {
        return buildShowColumnsSql(prefix, false);
    }

    private String buildShowColumnsSql(String prefix, boolean exactMatch) {
        String normalizedPrefix = safe(prefix);
        if (normalizedPrefix.isEmpty()) {
            return "show columns;";
        }

        String identifier = StorageUtils.quoteIdentifierPath(normalizedPrefix);
        if (normalizedPrefix.endsWith(".*")) {
            return "show columns " + identifier + ";";
        }
        if (exactMatch) {
            return "show columns " + identifier + ";";
        }
        return "show columns " + identifier + ".*;";
    }

    private long estimateExternalAssetSize(String assetPath, AddSourceContext context) {
        if (assetPath == null || assetPath.isEmpty() || context == null) {
            return 0L;
        }

        if (isFilesystemExternalSource(context)) {
            return estimateFilesystemExternalAssetSize(assetPath, context);
        }
        return estimateStructuredExternalAssetSize(assetPath, context);
    }

    private long estimateStructuredExternalAssetSize(String assetPath, AddSourceContext context) {
        List<ColumnSchema> columns = listExternalColumnSchemas(assetPath, context);
        if (columns.isEmpty()) {
            throw new IllegalStateException("No column schema found for structured asset: " + assetPath);
        }

        long rowCount = resolveExternalRowCount(assetPath);
        if (rowCount <= 0L) {
            rowCount = resolveStructuredRowCountFromFirstColumn(columns);
        }
        if (rowCount <= 0L) {
            return 0L;
        }

        if (rowCount < STRUCTURED_SAMPLE_ROW_COUNT) {
            return queryStructuredRowsTotalBytes(assetPath, 0);
        }

        long sampleBytes = queryStructuredRowsTotalBytes(assetPath, STRUCTURED_SAMPLE_ROW_COUNT);
        long avgPerRow = Math.max(1L, sampleBytes / STRUCTURED_SAMPLE_ROW_COUNT);
        if (avgPerRow > Long.MAX_VALUE / rowCount) {
            return Long.MAX_VALUE;
        }
        return avgPerRow * rowCount;
    }

    private long resolveStructuredRowCountFromFirstColumn(List<ColumnSchema> columns) {
        if (columns == null || columns.isEmpty()) {
            return 0L;
        }
        for (ColumnSchema column : columns) {
            if (column == null || column.path.isEmpty()) {
                continue;
            }
            try {
                String[] parentAndLeaf = splitParentAndLeafStrict(column.path);
                return resolveFilesystemRowCount(parentAndLeaf[0], parentAndLeaf[1]);
            } catch (Exception e) {
                logger.warn("[ExternalSource] Structured row count fallback failed. columnPath={}", column.path, e);
            }
        }
        return 0L;
    }

    private long estimateFilesystemExternalAssetSize(String assetPath) {
        String[] parentAndLeaf = splitParentAndLeafStrict(assetPath);
        String parentPath = parentAndLeaf[0];
        String leafField = parentAndLeaf[1];

        long rowCount = resolveFilesystemRowCount(parentPath, leafField);
        if (rowCount <= 0L) {
            return 0L;
        }

        long lastRowBytes = queryFilesystemRowBytes(parentPath, leafField, rowCount - 1L);
        
        if (rowCount == 1L) {
            return lastRowBytes;
        }

        long prefixRows = rowCount - 1L;

        if (FULL_ROW_BYTES > Long.MAX_VALUE / prefixRows) {
            return Long.MAX_VALUE;
        }

        long total = FULL_ROW_BYTES * prefixRows;
        if (Long.MAX_VALUE - total < lastRowBytes) {
            return Long.MAX_VALUE;
        }
        return total + Math.max(0L, lastRowBytes);
    }

    private long estimateFilesystemExternalAssetActualSize(String assetPath) {
        String[] parentAndLeaf = splitParentAndLeafStrict(assetPath);
        String parentPath = parentAndLeaf[0];
        String leafField = parentAndLeaf[1];
        String sql = "select " + StorageUtils.quoteIdentifierSegment(leafField)
                + " from " + StorageUtils.quoteIdentifierPath(parentPath) + ";";
        logger.info("[ExternalSource] Fetch filesystem actual bytes SQL: {}", sql);
        SessionExecuteSqlResult result = iginxDao.executeSql(sql);
        long bytes = sumResultValueBytes(result);
        logger.info("[ExternalSource] Filesystem actual bytes resolved. assetPath={}, bytes={}", assetPath, bytes);
        return bytes;
    }

    private long estimateFilesystemExternalAssetSize(String assetPath, AddSourceContext context) {
        if (context != null && "ssh".equals(context.sizeCalculationStrategy)) {
            return estimateFilesystemSizeBySSH(assetPath, context);
        }
        return estimateFilesystemExternalAssetSize(assetPath);
    }

    private long resolveFilesystemRowCount(String parentPath, String leafField) {
        String sql = "select count(" + StorageUtils.quoteIdentifierSegment(leafField) + ") from " + StorageUtils.quoteIdentifierPath(parentPath) + ";";
        logger.info("[ExternalSource] Resolve filesystem row count SQL: {}", sql);
        SessionExecuteSqlResult result = iginxDao.executeSql(sql);
        List<Long> counts = parseCountValues(result);
        if (counts.isEmpty()) {
            logger.warn("[ExternalSource] Filesystem row count is empty, fallback size=0. parentPath={}, leafField={}", parentPath, leafField);
            return 0L;
        }
        return counts.get(0).longValue();
    }

    private long queryFilesystemRowBytes(String parentPath, String leafField, long offset) {
        String sql = "select " + StorageUtils.quoteIdentifierSegment(leafField)
                + " from " + StorageUtils.quoteIdentifierPath(parentPath)
                + " limit 1 offset " + Math.max(0L, offset) + ";";
        logger.info("[ExternalSource] Fetch filesystem row SQL: {}", sql);
        SessionExecuteSqlResult result = iginxDao.executeSql(sql);
        return sumResultValueBytes(result);
    }

    private long queryStructuredRowsTotalBytes(String assetPath, int limit) {
        String sql = "select * from " + StorageUtils.quoteIdentifierPath(assetPath)
                + (limit > 0 ? " limit " + limit : "") + ";";
        logger.info("[ExternalSource] Fetch structured sample SQL: {}", sql);
        SessionExecuteSqlResult result = iginxDao.executeSql(sql);
        return sumResultValueBytes(result);
    }

    private String[] splitParentAndLeafStrict(String columnPath) {
        String normalizedPath = StorageUtils.normalizeEscapedPath(columnPath);
        String[] parentAndLeaf = StorageUtils.splitParentAndLeaf(normalizedPath);
        String parentPath = parentAndLeaf[0];
        String leafField = parentAndLeaf[1];
        if (parentPath.isEmpty() || leafField.isEmpty()) {
            throw new IllegalStateException("Invalid column path: " + columnPath);
        }
        return new String[]{parentPath, leafField};
    }

    private long sumResultValueBytes(SessionExecuteSqlResult result) {
        if (result == null || result.getValues() == null) {
            return 0L;
        }
        long totalBytes = 0L;
        for (List<Object> row : result.getValues()) {
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                if (value == null) {
                    continue;
                }
                long bytes = estimateValueBytes(value);
                if (bytes <= 0L) {
                    continue;
                }
                if (Long.MAX_VALUE - totalBytes < bytes) {
                    return Long.MAX_VALUE;
                }
                totalBytes += bytes;
            }
        }
        return totalBytes;
    }

    private void publishExternalSyncEvent(String level,
                                          String status,
                                          String logicalPath,
                                          String fileName,
                                          long estimatedSize,
                                          String detail) {
        String pathPart = safe(logicalPath).isEmpty() ? "(unknown)" : safe(logicalPath);
        String filePart = safe(fileName).isEmpty() ? "(unknown)" : safe(fileName);
        long safeSize = Math.max(0L, estimatedSize);
        String text = "外部数据解析: path=" + pathPart
                + ", file=" + filePart
                + ", size=" + safeSize + " bytes"
                + (safe(detail).isEmpty() ? "" : ", detail=" + safe(detail));
        metadataExtractionSchedulerService.publishExternalSourceEvent(level, status, text, "External-Source");
    }

    private String initialKnowledgeExtractStatus(String dataType, String fileName, String fileFormat) {
        return shouldSkipKnowledgeExtractionAtIngest(dataType, fileName, fileFormat) ? STATUS_SKIPPED : STATUS_PENDING;
    }

    private boolean shouldSkipKnowledgeExtractionAtIngest(String dataType, String fileName, String fileFormat) {
        if (!IGinxConstants.TYPE_FILE.equals(safe(dataType).toLowerCase(Locale.ROOT))) {
            return false;
        }
        String normalizedFileFormat = safe(fileFormat).toLowerCase(Locale.ROOT);
        if (normalizedFileFormat.startsWith(".")) {
            normalizedFileFormat = normalizedFileFormat.substring(1);
        }
        if (normalizedFileFormat.isEmpty()) {
            String normalizedFileName = safe(fileName).toLowerCase(Locale.ROOT);
            int dotIndex = normalizedFileName.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex + 1 < normalizedFileName.length()) {
                normalizedFileFormat = normalizedFileName.substring(dotIndex + 1);
            }
        }
        return !IMAGE_FILE_EXTENSIONS.contains(normalizedFileFormat);
    }

    private void publishInitialKnowledgeExtractEvent(String agentName,
                                                     String logicalPath,
                                                     String fileName,
                                                     String dataType,
                                                     String fileFormat,
                                                     String knowledgeExtractStatus) {
        String normalizedStatus = safe(knowledgeExtractStatus).toUpperCase(Locale.ROOT);
        if (!STATUS_SKIPPED.equals(normalizedStatus)) {
            return;
        }
        String pathPart = safe(logicalPath).isEmpty() ? "(unknown)" : safe(logicalPath);
        String filePart = safe(fileName).isEmpty() ? "(unknown)" : safe(fileName);
        String text = "Knowledge extraction skipped at ingest: path=" + pathPart
                + ", dataType=" + safe(dataType).toLowerCase(Locale.ROOT)
                + ", fileName=" + filePart
                + ", fileFormat=" + safe(fileFormat)
                + ", reason=non-image file blocked before extraction";
        metadataExtractionSchedulerService.publishExternalSourceEvent(
                "info",
                STATUS_SKIPPED,
                text,
                safe(agentName).isEmpty() ? "Storage-Service" : safe(agentName));
    }

    private long resolveExternalRowCount(String assetPath) {
        String quotedAssetPath = StorageUtils.quoteIdentifierPath(assetPath);
        String sql = "select count(*) from " + quotedAssetPath + ";";
        logger.info("[ExternalSource] Resolve structured row count SQL: {}", sql);
        SessionExecuteSqlResult result = iginxDao.executeSql(sql);
        List<Long> counts = parseCountValues(result);
        if (counts.isEmpty()) {
            logger.warn("[ExternalSource] Structured row count is empty, fallback size=0. assetPath={}", assetPath);
            return 0L;
        }
        return counts.get(0).longValue();
    }

    private List<Long> parseCountValues(SessionExecuteSqlResult result) {
        List<Long> counts = new ArrayList<Long>();
        if (result == null || result.getValues() == null) {
            return counts;
        }
        for (List<Object> row : result.getValues()) {
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                Long parsed = valueAsLong(value);
                if (parsed != null && parsed >= 0L) {
                    counts.add(parsed);
                }
            }
        }
        return counts;
    }

    private long estimateValueBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).length;
        }
        return valueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private boolean isColumnUnderAsset(String columnPath, String assetPath) {
        String safeColumn = safe(columnPath);
        String safeAsset = safe(assetPath);
        return safeColumn.equals(safeAsset) || safeColumn.startsWith(safeAsset + ".");
    }

    private Set<String> deriveAssetPaths(List<String> columnPaths, AddSourceContext context) {
        LinkedHashSet<String> assets = new LinkedHashSet<String>();
        if (columnPaths == null) {
            return assets;
        }

        boolean collapseByLastSegment = "mysql".equals(context.sourceType)
                || "postgres".equals(context.sourceType)
                || "iotdb".equals(context.sourceType);

        String expectedPrefix = context.schemaPrefix + ".";

        for (String rawPath : columnPaths) {
            String path = safe(rawPath);
            if (!path.startsWith(expectedPrefix)) {
                continue;
            }

            String assetPath = path;
            if ("redis".equals(context.sourceType)) {
                assetPath = context.schemaPrefix;
            } else if ("mongodb".equals(context.sourceType)) {
                assetPath = deriveMongoCollectionAssetPath(path, context);
            } else if (collapseByLastSegment && countPathSegments(path) > 3) {
                int splitIdx = findLastUnescapedDot(path);
                if (splitIdx > 0) {
                    assetPath = path.substring(0, splitIdx);
                }
            }

            if (countPathSegments(assetPath) >= 3) {
                assets.add(assetPath);
            }
        }

        return assets;
    }

    private String deriveMongoCollectionAssetPath(String columnPath, AddSourceContext context) {
        List<String> segments = splitUnescapedSegments(columnPath);
        int startIndex = splitUnescapedSegments(context.schemaPrefix).size();
        if (segments.size() >= startIndex + 2) {
            List<String> assetSegments = new ArrayList<String>();
            for (int i = 0; i < startIndex + 2; i++) {
                assetSegments.add(segments.get(i));
            }
            return joinEscapedPathSegments(assetSegments);
        }
        return columnPath;
    }

    private String toExternalLogicalPath(String assetPath, AddSourceContext context) {
        if ("filesystem".equals(context.sourceType)) {
            return toFilesystemFolderLogicalPath(assetPath, context);
        }

        List<String> bodySegments = extractExternalBodySegments(assetPath, context);
        StringBuilder sb = new StringBuilder();
        sb.append(EXTERN_LOGICAL_PREFIX).append("/").append(context.logicalSourceKey);

        int logicalEnd = bodySegments.size();
        if (isStructuredExternalSource(context) && logicalEnd > 0) {
            logicalEnd = logicalEnd - 1;
        }

        for (int i = 0; i < logicalEnd; i++) {
            String seg = safe(bodySegments.get(i));
            if (!seg.isEmpty()) {
                sb.append("/").append(seg);
            }
        }
        return normalizePath(sb.toString());
    }

    private String toFilesystemFolderLogicalPath(String assetPath, AddSourceContext context) {
        List<String> segments = splitUnescapedSegments(assetPath);
        String sourceKey = safe(context.logicalSourceKey);
        if (sourceKey.isEmpty()) {
            sourceKey = safe(context.sourceType);
        }
        if (segments.isEmpty()) {
            return normalizePath(EXTERN_LOGICAL_PREFIX + "/" + sourceKey);
        }

        int startIndex = splitUnescapedSegments(context.schemaPrefix).size();
        startIndex = Math.min(startIndex, Math.max(segments.size() - 1, 0));

        StringBuilder folder = new StringBuilder();
        folder.append(EXTERN_LOGICAL_PREFIX).append("/").append(sourceKey);
        for (int i = startIndex; i < segments.size() - 1; i++) {
            String seg = safe(segments.get(i));
            if (!seg.isEmpty()) {
                folder.append('/').append(seg);
            }
        }
        return normalizePath(folder.toString());
    }

    private String deriveExternalFileName(String assetPath, AddSourceContext context, String logicalPath) {
        if ("filesystem".equals(context.sourceType)) {
            List<String> segments = splitUnescapedSegments(assetPath);
            if (!segments.isEmpty()) {
                return normalizeFileName(segments.get(segments.size() - 1));
            }
        }

        if (isStructuredExternalSource(context)) {
            List<String> bodySegments = extractExternalBodySegments(assetPath, context);
            if (!bodySegments.isEmpty()) {
                return normalizeFileName(bodySegments.get(bodySegments.size() - 1));
            }
        }

        return normalizeFileName(getFileNameFromLogicalPath(logicalPath));
    }

    private List<String> extractExternalBodySegments(String assetPath, AddSourceContext context) {
        List<String> segments = splitUnescapedSegments(assetPath);
        List<String> body = new ArrayList<String>();
        if (segments.isEmpty() || context == null) {
            return body;
        }

        int startIndex = splitUnescapedSegments(context.schemaPrefix).size();
        if (segments.size() > startIndex) {
            String firstDataSegment = safe(segments.get(startIndex)).toLowerCase(Locale.ROOT);
            if (firstDataSegment.equals(context.sourceType)
                    || ("postgres".equals(context.sourceType) && "postgresql".equals(firstDataSegment))) {
                startIndex = startIndex + 1;
            }
        }

        for (int i = startIndex; i < segments.size(); i++) {
            String seg = safe(segments.get(i));
            if (!seg.isEmpty()) {
                body.add(seg);
            }
        }
        return body;
    }

    private boolean isStructuredExternalSource(AddSourceContext context) {
        if (context == null) {
            return false;
        }
        String t = safe(context.sourceType).toLowerCase(Locale.ROOT);
        return "mysql".equals(t) || "postgres".equals(t) || "iotdb".equals(t) || "mongodb".equals(t) || "redis".equals(t);
    }

    private boolean isFilesystemExternalSource(AddSourceContext context) {
        return context != null && "filesystem".equals(safe(context.sourceType).toLowerCase(Locale.ROOT));
    }

    private DataItem findMetaByPathAndFile(List<DataItem> items, String logicalPath, String fileName) {
        if (items == null) {
            return null;
        }
        String targetPath = normalizePath(logicalPath);
        String targetFile = normalizeFileName(fileName);
        DataItem picked = null;
        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            if (!targetPath.equals(normalizePath(item.getLogicalPath()))) {
                continue;
            }
            if (!targetFile.equals(normalizeFileName(item.getFileName()))) {
                continue;
            }
            if (picked == null || compareItemId(item, picked) > 0) {
                picked = item;
            }
        }
        return picked;
    }

    private DataItem findLegacyExternalStructuredMeta(List<DataItem> items,
                                                      String logicalPath,
                                                      String fileName,
                                                      AddSourceContext context) {
        if (items == null || !isStructuredExternalSource(context)) {
            return null;
        }
        String normalizedFile = normalizeFileName(fileName);
        if (normalizedFile.isEmpty()) {
            return null;
        }

        String legacyPath = normalizePath(logicalPath + "/" + normalizedFile);
        DataItem picked = null;
        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            if (!legacyPath.equals(normalizePath(item.getLogicalPath()))) {
                continue;
            }
            if (!normalizedFile.equals(normalizeFileName(item.getFileName()))) {
                continue;
            }
            if (picked == null || compareItemId(item, picked) > 0) {
                picked = item;
            }
        }
        return picked;
    }

    private int compareItemId(DataItem left, DataItem right) {
        int l = left == null || left.getId() == null ? Integer.MIN_VALUE : left.getId().intValue();
        int r = right == null || right.getId() == null ? Integer.MIN_VALUE : right.getId().intValue();
        return Integer.compare(l, r);
    }

    private List<String> splitUnescapedSegments(String text) {
        List<String> segments = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '.') {
                segments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (escaping) {
            current.append('\\');
        }
        segments.add(current.toString());
        return segments;
    }

    private int countPathSegments(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 1;
        boolean escaping = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '.') {
                count++;
            }
        }
        return count;
    }

    private int findLastUnescapedDot(String text) {
        boolean escaping = false;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '.') {
                return i;
            }
        }
        return -1;
    }

    private AddSourceContext validateAndBuildContext(AddStorageEngineRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        AddSourceContext context = new AddSourceContext();
        context.sourceType = safe(request.getSourceType()).toLowerCase(Locale.ROOT);
        context.ip = safe(request.getIp());
        context.port = request.getPort() == null ? -1 : request.getPort().intValue();
        context.username = safe(request.getUsername());
        context.password = safe(request.getPassword());
        context.dummyDir = safe(request.getDummyDir()).replace('\\', '/');
        context.iginxPort = request.getIginxPort() == null ? -1 : request.getIginxPort().intValue();
        context.schemaPrefix = DEFAULT_EXTERN_SCHEMA_PREFIX;
        context.logicalSourceKey = context.sourceType;
        context.sizeCalculationStrategy = safe(request.getSizeCalculationStrategy());
        context.sshUsername = safe(request.getSshUsername());
        context.sshPassword = safe(request.getSshPassword());
        context.sshPort = request.getSshPort() == null ? 22 : request.getSshPort().intValue();

        if (!("filesystem".equals(context.sourceType)
                || "mysql".equals(context.sourceType)
                || "postgres".equals(context.sourceType)
                || "iotdb".equals(context.sourceType)
                || "mongodb".equals(context.sourceType)
                || "redis".equals(context.sourceType))) {
            throw new IllegalArgumentException("sourceType only supports filesystem/mysql/postgres/iotdb/mongodb/redis");
        }

        if (context.ip.isEmpty()) {
            throw new IllegalArgumentException("IP 不能为空");
        }
        if (context.port <= 0) {
            throw new IllegalArgumentException("端口必须为正整数");
        }

        if ("filesystem".equals(context.sourceType)) {
            if (context.dummyDir.isEmpty()) {
                throw new IllegalArgumentException("filesystem 类型必须填写 dummy_dir");
            }
            if (context.iginxPort <= 0) {
                throw new IllegalArgumentException("filesystem 类型必须填写 iginx_port");
            }
            
            if ("ssh".equals(context.sizeCalculationStrategy)) {
                if (context.sshUsername.isEmpty()) {
                    throw new IllegalArgumentException("选择命令行获取策略时，SSH用户名不能为空");
                }
                if (context.sshPassword.isEmpty()) {
                    throw new IllegalArgumentException("选择命令行获取策略时，SSH密码不能为空");
                }
                if (context.sshPort <= 0 || context.sshPort > 65535) {
                    throw new IllegalArgumentException("SSH端口必须在 1-65535 之间");
                }
            }
            
            context.mappedDataType = IGinxConstants.TYPE_DOCUMENT;
        } else if ("iotdb".equals(context.sourceType)) {
            if (context.username.isEmpty() || context.password.isEmpty()) {
                throw new IllegalArgumentException("iotdb 类型必须填写 username 和 password");
            }
            context.mappedDataType = IGinxConstants.TYPE_TIMESERIES;
        } else if ("mongodb".equals(context.sourceType)) {
            context.mappedDataType = IGinxConstants.TYPE_DOCUMENT;
        } else if ("redis".equals(context.sourceType)) {
            context.mappedDataType = IGinxConstants.TYPE_KEYVALUE;
        } else {
            if (context.username.isEmpty() || context.password.isEmpty()) {
                throw new IllegalArgumentException("mysql/postgres 类型必须填写 username 和 password");
            }
            context.mappedDataType = IGinxConstants.TYPE_RELATIONAL;
        }

        context.schemaPrefix = computeSchemaPrefix(context);
        context.logicalSourceKey = deriveLogicalSourceKey(context.sourceType, context.schemaPrefix);

        return context;
    }

    private String buildAddStorageEngineSql(AddSourceContext context) {
        String ip = escapeOption(context.ip);
        String schemaPrefix = context.schemaPrefix;

        if ("filesystem".equals(context.sourceType)) {
            List<String> options = new ArrayList<String>();
            options.add("has_data \"true\"");
            options.add("is_read_only \"true\"");
            options.add("schema_prefix \"" + escapeOption(schemaPrefix) + "\"");
            options.add("dummy_dir \"" + escapeOption(context.dummyDir) + "\"");
            options.add("iginx_port \"" + context.iginxPort + "\"");
            options.add("dummy.struct \"" + DEFAULT_FILESYSTEM_STRUCT + "\"");
            options.add("dummy.config.formats.CSV.inferSchema \"true\"");
            return String.format(
                    Locale.ROOT,
                    "ADD STORAGEENGINE (\"%s\", %d, \"filesystem\", OPTIONS (%s));",
                    ip,
                    context.port,
                    joinOptions(options));
        }

        if ("mysql".equals(context.sourceType) || "postgres".equals(context.sourceType)) {
            String relationalEngine = "mysql".equals(context.sourceType) ? "mysql" : "postgresql";
            return String.format(
                    Locale.ROOT,
                    "ADD STORAGEENGINE (\"%s\", %d, \"relational\", OPTIONS (engine \"%s\", username \"%s\", password \"%s\", has_data \"true\", is_read_only \"true\", schema_prefix \"%s\"));",
                    ip,
                    context.port,
                    relationalEngine,
                    escapeOption(context.username),
                    escapeOption(context.password),
                    schemaPrefix);
        }

        if ("mongodb".equals(context.sourceType) || "redis".equals(context.sourceType)) {
            return String.format(
                    Locale.ROOT,
                    "ADD STORAGEENGINE (\"%s\", %d, \"%s\", OPTIONS (schema_prefix \"%s\"));",
                    ip,
                    context.port,
                    context.sourceType,
                    escapeOption(schemaPrefix));
        }

        return String.format(
                Locale.ROOT,
                "ADD STORAGEENGINE (\"%s\", %d, \"iotdb12\", OPTIONS (username \"%s\", password \"%s\", schema_prefix \"%s\"));",
                ip,
                context.port,
                escapeOption(context.username),
                escapeOption(context.password),
                schemaPrefix);
    }

    private void executeAddStorageEngineSql(AddSourceContext context, String sql) {
        if (context != null && "filesystem".equals(context.sourceType)) {
            iginxDao.executeSqlPreferEndpoint(sql, context.ip, Integer.valueOf(context.iginxPort));
            return;
        }
        iginxDao.executeSql(sql);
    }

    private String escapeOption(String value) {
        return safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String joinOptions(List<String> options) {
        StringBuilder sb = new StringBuilder();
        if (options == null) {
            return "";
        }
        for (String option : options) {
            if (safe(option).isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(option);
        }
        return sb.toString();
    }

    private String computeSchemaPrefix(AddSourceContext context) {
        if (context == null) {
            return DEFAULT_EXTERN_SCHEMA_PREFIX;
        }

        String sourceKey = buildExternalSourceKey(context.sourceType, context.ip, context.port);
        if (sourceKey.isEmpty()) {
            return DEFAULT_EXTERN_SCHEMA_PREFIX;
        }
        return DEFAULT_EXTERN_SCHEMA_PREFIX + "." + sourceKey;
    }

    private String buildExternalSourceKey(String sourceType, String ip, int port) {
        String typeToken = safe(sourceType).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String ipToken = safe(ip).replaceAll("[^a-zA-Z0-9]", "_").replaceAll("_+", "_");

        int portToken = port;
        if ("iotdb".equals(typeToken)
                && "127.0.0.1".equals(safe(ip))
                && port == 6667) {
            portToken = 6668;
        }

        if (typeToken.isEmpty() || ipToken.isEmpty() || portToken <= 0) {
            return "";
        }
        return typeToken + ipToken + "_" + portToken;
    }

    private String deriveLogicalSourceKey(String sourceType, String schemaPrefix) {
        String safeSourceType = safe(sourceType).toLowerCase(Locale.ROOT);
        String safePrefix = safe(schemaPrefix);
        String prefixHead = DEFAULT_EXTERN_SCHEMA_PREFIX + ".";
        if (safePrefix.startsWith(prefixHead) && safePrefix.length() > prefixHead.length()) {
            return safePrefix.substring(prefixHead.length());
        }
        return safeSourceType;
    }

    private String getFileNameFromLogicalPath(String logicalPath) {
        String normalized = normalizePath(logicalPath);
        int idx = normalized.lastIndexOf('/');
        if (idx < 0 || idx == normalized.length() - 1) {
            return normalized;
        }
        return normalized.substring(idx + 1);
    }

    private String normalizePath(String path) {
        if (path == null) return "/";
        path = path.trim();
        if (!path.startsWith("/")) path = "/" + path;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) return "";
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    private String normalizeFileName(String fileName) {
        String name = safe(fileName);
        if (name.contains("\\")) {
            name = name.replace("\\", "/");
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[\r\n]", "").trim();
    }

    private String canonicalDataType(String dataType) {
        return safe(dataType).toLowerCase(Locale.ROOT);
    }

    private String inferExternalDataType(AddSourceContext context, String fileName, String fileFormat) {
        if (context == null) {
            return IGinxConstants.TYPE_DOCUMENT;
        }
        if (!"filesystem".equals(context.sourceType)) {
            return context.mappedDataType;
        }

        String ext = safe(fileFormat).toLowerCase(Locale.ROOT);
        String lowerName = safe(fileName).toLowerCase(Locale.ROOT);

        if ("csv".equals(ext)) {
            return looksLikeTimeseriesName(lowerName)
                    ? IGinxConstants.TYPE_TIMESERIES
                    : IGinxConstants.TYPE_RELATIONAL;
        }
        return IGinxConstants.TYPE_FILE;
    }

    private boolean isImageExtension(String ext) {
        return "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "png".equals(ext)
                || "bmp".equals(ext)
                || "gif".equals(ext)
                || "webp".equals(ext);
    }

    private boolean isKeyValueExtension(String ext) {
        return "properties".equals(ext)
                || "env".equals(ext)
                || "yaml".equals(ext)
                || "yml".equals(ext);
    }

    private boolean isStructuredTextExtension(String ext) {
        return "csv".equals(ext)
                || "tsv".equals(ext)
                || "txt".equals(ext);
    }

    private boolean isDocumentExtension(String ext) {
        return "xml".equals(ext)
                || "json".equals(ext)
                || "md".equals(ext)
                || "rst".equals(ext)
                || "html".equals(ext)
                || "htm".equals(ext)
                || "pdf".equals(ext)
                || "doc".equals(ext)
                || "docx".equals(ext);
    }

    private boolean isExpandedDocumentExtension(String ext) {
        return "json".equals(ext)
                || "xml".equals(ext);
    }

    private boolean looksLikeTimeseriesName(String lowerName) {
        if (lowerName == null || lowerName.isEmpty()) {
            return false;
        }
        return lowerName.contains("_ts")
                || lowerName.contains("timeseries")
                || lowerName.contains("time_series")
                || lowerName.contains("timeserie")
                || lowerName.contains("metric")
                || lowerName.contains("telemetry");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private Long valueAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = valueAsString(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long estimateFilesystemSizeBySSH(String assetPath, AddSourceContext context) {
        try {
            String relativePath = extractRelativePathFromAsset(assetPath, context);

            if (context.sshFileSizeCache == null) {
                context.sshFileSizeCache = resolveSharedSshDirectoryFileSizes(context);
            }

            Long fileSize = context.sshFileSizeCache.get(relativePath);
            if (fileSize != null && fileSize > 0L) {
                return fileSize;
            }

            logger.warn("[ExternalSource] SSH cache did not contain size for file '{}'. Available files: {}", 
                    relativePath, context.sshFileSizeCache.keySet());
            return estimateFilesystemExternalAssetActualSize(assetPath);
        } catch (Exception e) {
            logger.error("[ExternalSource] Failed to estimate filesystem size by SSH, fallback to system calculation", e);
            return estimateFilesystemExternalAssetActualSize(assetPath);
        }
    }

    private Map<String, Long> resolveSharedSshDirectoryFileSizes(AddSourceContext context) throws Exception {
        SshDirectoryCacheKey cacheKey = SshDirectoryCacheKey.from(context);
        SshDirectoryFileCacheEntry cacheEntry;
        boolean loadOwner = false;
        long now = System.currentTimeMillis();
        synchronized (sshDirectoryFileCacheLock) {
            evictExpiredSshDirectoryCacheEntries(now);
            cacheEntry = sshDirectoryFileCache.get(cacheKey);
            if (cacheEntry == null) {
                cacheEntry = new SshDirectoryFileCacheEntry();
                sshDirectoryFileCache.put(cacheKey, cacheEntry);
                evictOverflowSshDirectoryCacheEntries();
                loadOwner = true;
            }
        }

        if (loadOwner) {
            logger.info("[ExternalSource] Filesystem size source=SSH_DU_A_WITH_SHARED_DIRECTORY_FILE_CACHE miss. directory={}",
                    cacheKey.directory);
            try {
                File scriptFile = scriptExecutionUtils.resolveScriptFile(
                        "scripts/calculate_filesystem_size.sh",
                        "文件大小计算脚本");
                Map<String, Long> fileSizes = executeSSHSizeScript(
                        scriptFile.getAbsolutePath(),
                        context.ip,
                        context.sshUsername,
                        context.sshPassword,
                        String.valueOf(context.sshPort),
                        context.dummyDir);
                Map<String, Long> cachedSizes = Collections.unmodifiableMap(new HashMap<String, Long>(fileSizes));
                cacheEntry.completedAtMs = System.currentTimeMillis();
                cacheEntry.fileSizes.complete(cachedSizes);
                logger.info("[ExternalSource] Filesystem size source=SSH_DU_A_WITH_SHARED_DIRECTORY_FILE_CACHE loaded. directory={}, files={}",
                        cacheKey.directory,
                        cachedSizes.size());
            } catch (Exception e) {
                cacheEntry.fileSizes.completeExceptionally(e);
                synchronized (sshDirectoryFileCacheLock) {
                    SshDirectoryFileCacheEntry current = sshDirectoryFileCache.get(cacheKey);
                    if (current == cacheEntry) {
                        sshDirectoryFileCache.remove(cacheKey);
                    }
                }
                throw e;
            }
        } else if (cacheEntry.fileSizes.isDone()) {
            logger.info("[ExternalSource] Filesystem size source=SSH_DIRECTORY_FILE_CACHE hit. directory={}, files={}",
                    cacheKey.directory,
                    cacheEntry.fileCount());
        } else {
            logger.info("[ExternalSource] Filesystem size source=SSH_DIRECTORY_FILE_CACHE wait. directory={}",
                    cacheKey.directory);
        }

        try {
            return cacheEntry.fileSizes.get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shared SSH directory file sizes", e);
        }
    }

    private void evictExpiredSshDirectoryCacheEntries(long now) {
        long ttlMs = Math.max(0L, sshDirectoryCacheTtlMs);
        if (ttlMs == 0L) {
            return;
        }
        Iterator<Map.Entry<SshDirectoryCacheKey, SshDirectoryFileCacheEntry>> iterator =
                sshDirectoryFileCache.entrySet().iterator();
        while (iterator.hasNext()) {
            SshDirectoryFileCacheEntry entry = iterator.next().getValue();
            if (entry.completedAtMs > 0L && now - entry.completedAtMs >= ttlMs) {
                iterator.remove();
            }
        }
    }

    private void evictOverflowSshDirectoryCacheEntries() {
        int maxEntries = Math.max(1, sshDirectoryCacheMaxEntries);
        Iterator<Map.Entry<SshDirectoryCacheKey, SshDirectoryFileCacheEntry>> iterator =
                sshDirectoryFileCache.entrySet().iterator();
        while (sshDirectoryFileCache.size() > maxEntries && iterator.hasNext()) {
            SshDirectoryFileCacheEntry entry = iterator.next().getValue();
            if (entry.fileSizes.isDone()) {
                iterator.remove();
            }
        }
    }

    private String extractRelativePathFromAsset(String assetPath, AddSourceContext context) {
        String normalizedAssetPath = StorageUtils.normalizeEscapedPath(assetPath);
        String normalizedSchemaPrefix = StorageUtils.normalizeEscapedPath(context.schemaPrefix);
        
        if (!normalizedAssetPath.startsWith(normalizedSchemaPrefix + ".")) {
            String[] parentAndLeaf = splitParentAndLeafStrict(assetPath);
            return parentAndLeaf[1].replace("\\.", ".");
        }
        
        String pathAfterPrefix = normalizedAssetPath.substring(normalizedSchemaPrefix.length() + 1);
        
        List<String> segments = splitUnescapedSegments(pathAfterPrefix);
        if (segments.isEmpty()) {
            return "";
        }
        
        segments.remove(0);
        
        StringBuilder relativePath = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                relativePath.append("/");
            }
            relativePath.append(segments.get(i));
        }
        
        return relativePath.toString();
    }

    private Map<String, Long> executeSSHSizeScript(String scriptPath, String ip, String username, 
                                                   String password, String sshPort, String targetDir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(scriptPath);
        command.add(ip);
        command.add(username);
        command.add(password);
        command.add(sshPort);
        command.add(targetDir);
        
        ScriptExecutionUtils.ScriptExecutionResult result = scriptExecutionUtils.executeScript(command, 120);
        
        if (!result.isSuccess()) {
            throw new RuntimeException("SSH size calculation script failed with exit code: " + result.getExitCode() + 
                    ", error: " + result.getError());
        }
        
        return parseSSHSizeScriptOutput(result.getOutput());
    }

    private Map<String, Long> parseSSHSizeScriptOutput(String jsonOutput) {
        Map<String, Long> fileSizeMap = new HashMap<>();
        try {
            logger.info("[ExternalSource] Parsing SSH script output: {}", jsonOutput);
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonOutput);
            
            if (!root.has("success") || !root.get("success").asBoolean()) {
                throw new RuntimeException("SSH script execution failed");
            }
            
            com.fasterxml.jackson.databind.JsonNode files = root.get("files");
            if (files != null && files.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode file : files) {
                    long sizeInKB = file.get("size").asLong();
                    String path = file.get("path").asText();
                    
                    long sizeInBytes = sizeInKB * 1024L;
                    
//                    logger.info("[ExternalSource] Parsed file from SSH script - path: '{}', sizeInKB: {}, sizeInBytes: {}",
//                            path, sizeInKB, sizeInBytes);
                    
                    fileSizeMap.put(path, sizeInBytes);
                }
            }
        } catch (Exception e) {
            logger.error("[ExternalSource] Failed to parse SSH script output", e);
            throw new RuntimeException("Failed to parse SSH script output: " + e.getMessage(), e);
        }
        return fileSizeMap;
    }

    private static class AddSourceContext {
        private String sourceType;
        private String ip;
        private int port;
        private String username;
        private String password;
        private String dummyDir;
        private int iginxPort;
        private String schemaPrefix;
        private String logicalSourceKey;
        private String mappedDataType;
        private String sizeCalculationStrategy;
        private String sshUsername;
        private String sshPassword;
        private int sshPort;
        private Map<String, Long> sshFileSizeCache;
    }

    private static class SshDirectoryCacheKey {
        private final String host;
        private final int port;
        private final String username;
        private final String directory;

        private SshDirectoryCacheKey(String host, int port, String username, String directory) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.directory = directory;
        }

        private static SshDirectoryCacheKey from(AddSourceContext context) {
            String directory = context == null || context.dummyDir == null ? "" : context.dummyDir.trim().replace('\\', '/');
            while (directory.length() > 1 && directory.endsWith("/")) {
                directory = directory.substring(0, directory.length() - 1);
            }
            return new SshDirectoryCacheKey(
                    context == null || context.ip == null ? "" : context.ip.trim(),
                    context == null ? 0 : context.sshPort,
                    context == null || context.sshUsername == null ? "" : context.sshUsername.trim(),
                    directory);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SshDirectoryCacheKey)) {
                return false;
            }
            SshDirectoryCacheKey that = (SshDirectoryCacheKey) other;
            return port == that.port
                    && host.equals(that.host)
                    && username.equals(that.username)
                    && directory.equals(that.directory);
        }

        @Override
        public int hashCode() {
            int result = host.hashCode();
            result = 31 * result + port;
            result = 31 * result + username.hashCode();
            result = 31 * result + directory.hashCode();
            return result;
        }
    }

    private static class SshDirectoryFileCacheEntry {
        private final CompletableFuture<Map<String, Long>> fileSizes = new CompletableFuture<Map<String, Long>>();
        private volatile long completedAtMs;

        private int fileCount() {
            if (!fileSizes.isDone() || fileSizes.isCompletedExceptionally()) {
                return 0;
            }
            try {
                return fileSizes.getNow(Collections.<String, Long>emptyMap()).size();
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private static class ExternalMetaSyncResult {
        private int discoveredCount;
        private int importedCount;
        private int skippedCount;
        private int replacedCount;
        private long totalEstimatedSize;
        private final List<String> importedLogicalPaths = new ArrayList<String>();
        private final List<DataItem> importedItems = new ArrayList<DataItem>();
    }

    private static class ColumnSchema {
        private final String path;
        private final String type;

        private ColumnSchema(String path, String type) {
            this.path = path == null ? "" : path.trim();
            this.type = type == null ? "" : type.trim();
        }
    }

    private static class FilesystemSchemaAsset {
        private final String assetPath;
        private final List<ColumnSchema> columns = new ArrayList<ColumnSchema>();

        private FilesystemSchemaAsset(String assetPath) {
            this.assetPath = assetPath == null ? "" : assetPath.trim();
        }
    }
}
