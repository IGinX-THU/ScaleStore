package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AddStorageEngineRequest;
import com.storage.engine.model.DataItem;
import com.storage.engine.service.adapter.StorageAdapter;
import com.storage.engine.service.adapter.StorageAdapterFactory;
import com.storage.engine.service.adapter.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StorageService {

    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    private static final String DEFAULT_EXTERN_SCHEMA_PREFIX = "data.extern";
    private static final String EXTERN_LOGICAL_PREFIX = "/extern";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BINARY_COLUMN_SAMPLE_LIMIT = 100;
    private static final int BINARY_VALUE_PREVIEW_LIMIT = 100;
    private static final long DEFAULT_BINARY_BYTES = 16L;

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private StorageAdapterFactory adapterFactory;

    @Autowired
    private AccessService accessService;

    public synchronized Map<String, Object> addExternalStorageEngine(AddStorageEngineRequest request) {
        AddSourceContext context = validateAndBuildContext(request);
        String sql = buildAddStorageEngineSql(context);

        logger.info("[ExternalSource] ADD STORAGEENGINE SQL: {}", sql);

        executeAddStorageEngineSql(context, sql);

        ExternalMetaSyncResult syncResult = syncExternalMetadata(context);
        logger.info(
            "[ExternalSource] Metadata sync finished. sourceType={}, discovered={}, imported={}, skipped={}, replaced={}",
            context.sourceType,
            syncResult.discoveredCount,
            syncResult.importedCount,
            syncResult.skippedCount,
            syncResult.replacedCount);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sql", sql);
        payload.put("sourceType", context.sourceType);
        payload.put("schemaPrefix", context.schemaPrefix);
        payload.put("mappedDataType", context.mappedDataType);
        payload.put("discoveredAssetCount", syncResult.discoveredCount);
        payload.put("importedMetaCount", syncResult.importedCount);
        payload.put("skippedMetaCount", syncResult.skippedCount);
        payload.put("replacedMetaCount", syncResult.replacedCount);
        payload.put("importedLogicalPaths", syncResult.importedLogicalPaths);
        return payload;
    }

    /**
     * Store a file to IGinX using the adapter pattern.
        * Logical path is treated as a folder and can hold multiple files.
     */
    public synchronized DataItem storeData(MultipartFile file, String logicalPath, String dataType) throws Exception {
        // Normalize logical path
        logicalPath = normalizePath(logicalPath);

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
        long metaId = iginxDao.getMaxMetaId() + 1;

        // Convert logical folder path to a file-scoped IGinX path.
        String folderPath = StorageUtils.toIginxDataPath(logicalPath);
        String iginxPath = StorageUtils.toFileLeafPath(folderPath, fileName);
        String contentPath = buildContentPath(iginxPath, dataType);

        // Store metadata
        iginxDao.insertMeta(metaId, logicalPath, dataType, fileName, contentPath, fileSize, fileFormat, createTime);

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
        item.setKnowledgeExtractStatus("PENDING");
        item.setContentPath(contentPath);

        return item;
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
                || IGinxConstants.TYPE_KEYVALUE.equals(type);
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

        long nextMetaId = iginxDao.getMaxMetaId() + 1;
        String createTime = LocalDateTime.now().format(TIME_FORMATTER);
        List<DataItem> existingMeta = accessService.getAllMeta();

        for (String assetPath : assetPaths) {
            String logicalPath = toExternalLogicalPath(assetPath, context);
            String fileName = deriveExternalFileName(assetPath, context, logicalPath);
            DataItem existing = findMetaByPathAndFile(existingMeta, logicalPath, fileName);
            if (existing != null) {
                result.skippedCount++;
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

            iginxDao.insertMeta(
                    nextMetaId,
                    logicalPath,
                    inferredDataType,
                    fileName,
                    contentPath,
                    estimatedFileSize,
                    fileFormat,
                    createTime);

            result.importedCount++;
            result.importedLogicalPaths.add(normalizePath(logicalPath + "/" + fileName));

            DataItem added = new DataItem();
            added.setId((int) nextMetaId);
            added.setLogicalPath(logicalPath);
            added.setFileName(fileName);
            added.setDataType(inferredDataType);
            added.setContentPath(contentPath);
            added.setFileSize(estimatedFileSize);
            existingMeta.add(added);

            nextMetaId++;
        }

        return result;
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

    private List<ColumnSchema> parseShowColumnsSchemas(SessionExecuteSqlResult result) {
        List<ColumnSchema> out = new ArrayList<ColumnSchema>();
        if (result == null) {
            return out;
        }

        List<String> headers = result.getPaths();
        List<List<Object>> rows = result.getValues();

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
                out.add(new ColumnSchema(path, type));
            }
        }

        if (out.isEmpty() && headers != null) {
            for (String headerPath : headers) {
                if (headerPath != null) {
                    out.add(new ColumnSchema(headerPath, ""));
                }
            }
        }

        return out;
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
        if (normalizedPrefix.endsWith(".*")) {
            return "show columns " + normalizedPrefix + ";";
        }
        if (exactMatch) {
            return "show columns " + normalizedPrefix + ";";
        }
        return "show columns " + normalizedPrefix + ".*;";
    }

    private long estimateExternalAssetSize(String assetPath, AddSourceContext context) {
        if (assetPath == null || assetPath.isEmpty() || context == null) {
            return 0L;
        }

        List<ColumnSchema> columns = listExternalColumnSchemas(assetPath, context);
        if (columns.isEmpty()) {
            return 0L;
        }

        long rowCount = isFilesystemExternalSource(context) ? 1L : resolveExternalRowCount(assetPath);
        if (rowCount <= 0L) {
            return 0L;
        }

        long bytesPerRow = estimateRowSizeInBytes(columns);
        if (bytesPerRow <= 0L) {
            return 0L;
        }

        if (bytesPerRow > Long.MAX_VALUE / rowCount) {
            return Long.MAX_VALUE;
        }
        return bytesPerRow * rowCount;
    }

    private long resolveExternalRowCount(String assetPath) {
        SessionExecuteSqlResult result = iginxDao.executeSql("select count(*) from " + assetPath + ";");
        List<Long> counts = parseCountValues(result);
        if (counts.isEmpty()) {
            return 0L;
        }
        return Collections.max(counts);
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

    private long estimateRowSizeInBytes(List<ColumnSchema> columns) {
        long fixedBytes = 0L;
        int binaryColumns = 0;
        for (ColumnSchema column : columns) {
            if (column == null) {
                continue;
            }
            Long typeBytes = resolveFixedTypeBytes(column.type);
            if (typeBytes != null) {
                fixedBytes += typeBytes;
            } else {
                binaryColumns++;
            }
        }

        if (binaryColumns == 0) {
            return fixedBytes;
        }

        long averageBinaryBytes = estimateAverageBinaryColumnBytes(columns, BINARY_COLUMN_SAMPLE_LIMIT);
        if (averageBinaryBytes <= 0L) {
            averageBinaryBytes = DEFAULT_BINARY_BYTES;
        }
        return fixedBytes + averageBinaryBytes * binaryColumns;
    }

    private Long resolveFixedTypeBytes(String rawType) {
        String type = safe(rawType).toUpperCase(Locale.ROOT);
        switch (type) {
            case "BOOLEAN":
            case "INTEGER":
            case "FLOAT":
                return 4L;
            case "LONG":
            case "DOUBLE":
                return 8L;
            case "BINARY":
            default:
                return null;
        }
    }

    private long estimateAverageBinaryColumnBytes(List<ColumnSchema> columns, int sampleLimit) {
        List<ColumnSchema> binaryColumns = new ArrayList<ColumnSchema>();
        for (ColumnSchema column : columns) {
            if (column == null) {
                continue;
            }
            if (resolveFixedTypeBytes(column.type) == null) {
                binaryColumns.add(column);
            }
        }
        if (binaryColumns.isEmpty()) {
            return 0L;
        }

        int effectiveLimit = Math.min(Math.max(sampleLimit, 1), binaryColumns.size());
        long totalBytes = 0L;
        int sampled = 0;
        for (int i = 0; i < effectiveLimit; i++) {
            long averageBytes = estimateBinaryColumnAverageBytes(binaryColumns.get(i));
            if (averageBytes <= 0L) {
                continue;
            }
            totalBytes += averageBytes;
            sampled++;
        }

        if (sampled == 0) {
            return 0L;
        }
        return Math.max(1L, totalBytes / sampled);
    }

    private long estimateBinaryColumnAverageBytes(ColumnSchema column) {
        if (column == null || column.path.isEmpty()) {
            return 0L;
        }

        SessionExecuteSqlResult result = queryBinaryColumnValues(column.path);
        if (result == null || result.getValues() == null) {
            return 0L;
        }

        long totalBytes = 0L;
        long samples = 0L;
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
                totalBytes += bytes;
                samples++;
            }
        }

        if (samples == 0L) {
            return 0L;
        }
        return Math.max(1L, totalBytes / samples);
    }

    private SessionExecuteSqlResult queryBinaryColumnValues(String columnPath) {
        String normalizedPath = StorageUtils.normalizeEscapedPath(columnPath);
        String[] parentAndLeaf = StorageUtils.splitParentAndLeaf(normalizedPath);
        String parentPath = parentAndLeaf[0];
        String leafField = parentAndLeaf[1];
        if (!parentPath.isEmpty() && !leafField.isEmpty()) {
            String sql = "select " + leafField + " from " + parentPath + " limit " + BINARY_VALUE_PREVIEW_LIMIT + ";";
            return iginxDao.executeSql(sql);
        }
        return iginxDao.queryDataByPathWithLimit(normalizedPath, BINARY_VALUE_PREVIEW_LIMIT);
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
            if (collapseByLastSegment && countPathSegments(path) > 3) {
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
        return "mysql".equals(t) || "postgres".equals(t) || "iotdb".equals(t);
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

        if (!("filesystem".equals(context.sourceType)
                || "mysql".equals(context.sourceType)
                || "postgres".equals(context.sourceType)
                || "iotdb".equals(context.sourceType))) {
            throw new IllegalArgumentException("sourceType 仅支持 filesystem/mysql/postgres/iotdb");
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
            context.mappedDataType = IGinxConstants.TYPE_DOCUMENT;
        } else if ("iotdb".equals(context.sourceType)) {
            if (context.username.isEmpty() || context.password.isEmpty()) {
                throw new IllegalArgumentException("iotdb 类型必须填写 username 和 password");
            }
            context.mappedDataType = IGinxConstants.TYPE_TIMESERIES;
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
            return String.format(
                    Locale.ROOT,
                    "ADD STORAGEENGINE (\"%s\", %d, \"filesystem\", OPTIONS (has_data \"true\", is_read_only \"true\", schema_prefix \"%s\", dummy_dir \"%s\", iginx_port \"%d\"));",
                    ip,
                    context.port,
                    schemaPrefix,
                    escapeOption(context.dummyDir),
                    context.iginxPort);
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

    private String inferExternalDataType(AddSourceContext context, String fileName, String fileFormat) {
        if (context == null) {
            return IGinxConstants.TYPE_DOCUMENT;
        }
        if (!"filesystem".equals(context.sourceType)) {
            return context.mappedDataType;
        }

        String ext = safe(fileFormat).toLowerCase(Locale.ROOT);
        String lowerName = safe(fileName).toLowerCase(Locale.ROOT);

        if (isImageExtension(ext)) {
            return IGinxConstants.TYPE_IMAGE;
        }

        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".gif") || lowerName.endsWith(".webp")) {
            return IGinxConstants.TYPE_IMAGE;
        }

        return IGinxConstants.TYPE_DOCUMENT;
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
        return "json".equals(ext)
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
                || "md".equals(ext)
                || "rst".equals(ext)
                || "html".equals(ext)
                || "htm".equals(ext)
                || "pdf".equals(ext)
                || "doc".equals(ext)
                || "docx".equals(ext);
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
    }

    private static class ExternalMetaSyncResult {
        private int discoveredCount;
        private int importedCount;
        private int skippedCount;
        private int replacedCount;
        private final List<String> importedLogicalPaths = new ArrayList<String>();
    }

    private static class ColumnSchema {
        private final String path;
        private final String type;

        private ColumnSchema(String path, String type) {
            this.path = path == null ? "" : path.trim();
            this.type = type == null ? "" : type.trim();
        }
    }
}
