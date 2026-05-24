package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.DataItem;
import com.storage.engine.service.adapter.StorageAdapter;
import com.storage.engine.service.adapter.StorageAdapterFactory;
import com.storage.engine.service.adapter.StorageUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AccessService {

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private StorageAdapterFactory adapterFactory;

    /**
     * Get metadata for a data item by logical path (exact match only, valid entries).
     */
    public DataItem getMetaByPath(String logicalPath) {
        logicalPath = normalizePath(logicalPath);
        try {
            SessionExecuteSqlResult result = iginxDao.getAllMeta();
            List<DataItem> items = parseMeta(result);
            for (DataItem item : items) {
                if (logicalPath.equals(normalizePath(item.getLogicalPath()))) {
                    return item;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("按逻辑路径查询元数据失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Get metadata by folder logical path + file name.
     */
    public DataItem getMetaByPathAndFileName(String logicalPath, String fileName) {
        String folder = normalizePath(logicalPath);
        String name = normalizeFileName(fileName);
        if (name.isEmpty()) {
            return null;
        }

        try {
            SessionExecuteSqlResult result = iginxDao.getAllMeta();
            List<DataItem> items = parseMeta(result);
            return findByFolderAndFileExact(items, folder, name);
        } catch (Exception e) {
            throw new RuntimeException("按目录和文件名查询元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve metadata from access input path.
     * - /folder/file.ext => exact file under folder
     * - /folder => file only when folder has exactly one file
     */
    public DataItem getMetaByAccessPath(String accessPath) {
        return getMetaByAccessPath(accessPath, null);
    }

    public DataItem getMetaByAccessPath(String accessPath, String fileName) {
        String normalized = normalizePath(accessPath);
        List<DataItem> allMeta = getAllMeta();

        String targetFile = normalizeFileName(fileName);
        if (!targetFile.isEmpty()) {
            return findByFolderAndFile(allMeta, normalized, targetFile);
        }
        return null;
    }

    /**
     * Get all stored data items metadata.
     */
    public List<DataItem> getAllMeta() {
        try {
            SessionExecuteSqlResult result = iginxDao.getAllMeta();
            return parseMeta(result);
        } catch (Exception e) {
            throw new RuntimeException("查询全部元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * Access data by logical path.
     * - Exact match → returns metadata + preview from the adapter.
     * - No exact match, but is a prefix of children → returns a "directory" listing.
     * - Nothing found → returns null.
     */
    public DataItem accessData(String logicalPath) {
        return accessData(logicalPath, null);
    }

    public DataItem accessData(String logicalPath, String fileName) {
        logicalPath = normalizePath(logicalPath);

        List<DataItem> allMeta = getAllMeta();

        String targetFile = normalizeFileName(fileName);
        if (!targetFile.isEmpty()) {
            DataItem direct = findByFolderAndFile(allMeta, logicalPath, targetFile);
            if (direct == null) {
                return null;
            }
            return loadPreview(direct);
        }

        List<DataItem> selfFiles = listByLogicalPath(allMeta, logicalPath);
        Map<String, String> childFolders = listImmediateChildFolders(allMeta, logicalPath);

        if (!childFolders.isEmpty() || !selfFiles.isEmpty()) {
            return buildDirectoryItem(logicalPath, selfFiles, childFolders);
        }

        return null;
    }

    /**
     * Get raw data bytes for download (delegates to the adapter).
     */
    public byte[] downloadData(String logicalPath) {
        return downloadData(logicalPath, null);
    }

    public byte[] downloadData(String logicalPath, String fileName) {
        DataItem meta = getMetaByAccessPath(logicalPath, fileName);
        if (meta == null) return null;

        String iginxPath = resolveIginxDataPath(meta);
        String dataType = meta.getDataType();

        try {
            StorageAdapter adapter = adapterFactory.getAdapter(dataType);
            return adapter.getDownloadBytes(iginxPath);
        } catch (Exception e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }

    private DataItem loadPreview(DataItem meta) {
        String iginxPath = resolveIginxDataPath(meta);
        String dataType = meta.getDataType();

        try {
            StorageAdapter adapter = adapterFactory.getAdapter(dataType);
            Object previewData = adapter.getPreviewData(iginxPath, 50);
            meta.setPreviewData(previewData);
        } catch (Exception e) {
            throw new RuntimeException("预览失败: " + e.getMessage(), e);
        }
        return meta;
    }

    private List<DataItem> listByLogicalPath(List<DataItem> items, String logicalPath) {
        List<DataItem> out = new ArrayList<DataItem>();
        if (items == null) {
            return out;
        }
        String target = normalizePath(logicalPath);
        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            String itemPath = normalizePath(item.getLogicalPath());
            if (target.equals(itemPath) || isLegacyExternalStructuredItemInParent(item, target)) {
                out.add(item);
            }
        }
        return out;
    }

    private DataItem findByFolderAndFile(List<DataItem> items, String folderPath, String fileName) {
        if (items == null || fileName == null || fileName.isEmpty()) {
            return null;
        }

        String targetFolder = normalizePath(folderPath);
        String targetFile = normalizeFileName(fileName);
        DataItem picked = null;

        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            String folder = normalizePath(item.getLogicalPath());
            String name = normalizeFileName(item.getFileName());
            if (!targetFolder.equals(folder) || !targetFile.equals(name)) {
                continue;
            }

            if (picked == null || compareId(item.getId(), picked.getId()) > 0) {
                picked = item;
            }
        }

        if (picked != null) {
            return picked;
        }

        // Compatibility for legacy external structured metadata shape:
        // logicalPath=/extern/.../school/lessons, fileName=lessons.
        String legacyFolder = normalizePath(targetFolder + "/" + targetFile);
        for (DataItem item : items) {
            if (item == null || !isExternalStructuredDataItem(item)) {
                continue;
            }
            String folder = normalizePath(item.getLogicalPath());
            String name = normalizeFileName(item.getFileName());
            if (!legacyFolder.equals(folder) || !targetFile.equals(name)) {
                continue;
            }
            if (picked == null || compareId(item.getId(), picked.getId()) > 0) {
                picked = item;
            }
        }
        return picked;
    }

    private DataItem findByFolderAndFileExact(List<DataItem> items, String folderPath, String fileName) {
        if (items == null || fileName == null || fileName.isEmpty()) {
            return null;
        }

        String targetFolder = normalizePath(folderPath);
        String targetFile = normalizeFileName(fileName);
        DataItem picked = null;

        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            String folder = normalizePath(item.getLogicalPath());
            String name = normalizeFileName(item.getFileName());
            if (!targetFolder.equals(folder) || !targetFile.equals(name)) {
                continue;
            }
            if (picked == null || compareId(item.getId(), picked.getId()) > 0) {
                picked = item;
            }
        }
        return picked;
    }

    private DataItem buildDirectoryItem(String logicalPath,
                                        List<DataItem> folderItems,
                                        Map<String, String> childFolders) {
        List<Map<String, Object>> children = new ArrayList<Map<String, Object>>();

        List<String> folderPaths = new ArrayList<String>();
        if (childFolders != null) {
            folderPaths.addAll(childFolders.keySet());
        }
        Collections.sort(folderPaths);

        for (String folderPath : folderPaths) {
            Map<String, Object> child = new LinkedHashMap<String, Object>();
            child.put("name", lastPathSegment(folderPath));
            child.put("fullPath", folderPath);
            child.put("dataType", "directory");
            children.add(child);
        }

        Map<String, DataItem> dedup = new LinkedHashMap<String, DataItem>();
        for (DataItem item : folderItems) {
            if (item == null) {
                continue;
            }
            String key = normalizeFileName(item.getFileName());
            if (key.isEmpty()) {
                key = "item-" + (item.getId() == null ? 0 : item.getId());
            }
            DataItem existing = dedup.get(key);
            if (existing == null || compareId(item.getId(), existing.getId()) > 0) {
                dedup.put(key, item);
            }
        }

        List<String> names = new ArrayList<String>(dedup.keySet());
        Collections.sort(names);

        for (String name : names) {
            DataItem childItem = dedup.get(name);
            Map<String, Object> child = new LinkedHashMap<String, Object>();
            child.put("name", name);
            child.put("fullPath", normalizePath(logicalPath + "/" + name));
            child.put("dataType", childItem.getDataType());
            child.put("fileName", childItem.getFileName());
            child.put("createTime", childItem.getCreateTime());
            children.add(child);
        }

        DataItem folder = new DataItem();
        folder.setLogicalPath(logicalPath);
        folder.setDataType("directory");
        folder.setPreviewData(children);
        return folder;
    }

    private Map<String, String> listImmediateChildFolders(List<DataItem> items, String parentPath) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (items == null) {
            return out;
        }

        String parent = normalizePath(parentPath);
        String prefix = "/".equals(parent) ? "/" : parent + "/";

        for (DataItem item : items) {
            if (item == null) {
                continue;
            }
            String itemPath = normalizePath(item.getLogicalPath());
            if (!itemPath.startsWith(prefix) || itemPath.equals(parent)) {
                continue;
            }

            String remainder = itemPath.substring(prefix.length());
            if (remainder.isEmpty()) {
                continue;
            }

            String childName = remainder.contains("/")
                    ? remainder.substring(0, remainder.indexOf('/'))
                    : remainder;
            if (childName.isEmpty()) {
                continue;
            }

            // Legacy external structured metadata keeps file name as last path segment.
            // Treat it as file, not directory, when listing parent folder.
            if (!remainder.contains("/")
                    && isExternalStructuredDataItem(item)
                    && childName.equals(normalizeFileName(item.getFileName()))) {
                continue;
            }

            String childFullPath = "/".equals(parent) ? "/" + childName : parent + "/" + childName;
            out.put(normalizePath(childFullPath), childName);
        }

        return out;
    }

    private String lastPathSegment(String path) {
        String p = normalizePath(path);
        int idx = p.lastIndexOf('/');
        if (idx < 0 || idx == p.length() - 1) {
            return p;
        }
        return p.substring(idx + 1);
    }

    private int compareId(Integer left, Integer right) {
        int l = left == null ? Integer.MIN_VALUE : left.intValue();
        int r = right == null ? Integer.MIN_VALUE : right.intValue();
        return Integer.compare(l, r);
    }

    // ==================== Parse Helpers ====================

    private List<DataItem> parseMeta(SessionExecuteSqlResult result) {
        List<DataItem> items = new ArrayList<>();
        if (result == null) return items;

        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        if (keys == null || values == null || paths == null) return items;

        int pathIdx = -1, typeIdx = -1, nameIdx = -1, contentPathIdx = -1, sizeIdx = -1, formatIdx = -1, timeIdx = -1, validIdx = -1, knowledgeStatusIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("logicalPath")) pathIdx = i;
            else if (path.endsWith("dataType")) typeIdx = i;
            else if (path.endsWith("fileName")) nameIdx = i;
            else if (path.endsWith("contentPath")) contentPathIdx = i;
            else if (path.endsWith("fileSize")) sizeIdx = i;
            else if (path.endsWith("fileFormat")) formatIdx = i;
            else if (path.endsWith("createTime")) timeIdx = i;
            else if (path.endsWith("isValid")) validIdx = i;
            else if (path.endsWith("knowledgeExtractStatus")) knowledgeStatusIdx = i;
        }

        for (int i = 0; i < keys.length; i++) {
            List<Object> row = values.get(i);

            boolean isValid = true;
            if (validIdx != -1) {
                isValid = getValueAsBoolean(row.get(validIdx));
            }
            if (!isValid) continue;

            DataItem item = new DataItem();
            item.setId((int) keys[i]);
            if (pathIdx != -1) item.setLogicalPath(getValueAsString(row.get(pathIdx)));
            if (typeIdx != -1) item.setDataType(getValueAsString(row.get(typeIdx)));
            if (nameIdx != -1) item.setFileName(getValueAsString(row.get(nameIdx)));
            if (contentPathIdx != -1) item.setContentPath(getValueAsString(row.get(contentPathIdx)));
            if (sizeIdx != -1) item.setFileSize(getValueAsLong(row.get(sizeIdx)));
            if (formatIdx != -1) item.setFileFormat(getValueAsString(row.get(formatIdx)));
            if (timeIdx != -1) item.setCreateTime(getValueAsString(row.get(timeIdx)));
            if (knowledgeStatusIdx != -1) item.setKnowledgeExtractStatus(getValueAsString(row.get(knowledgeStatusIdx)));
            item.setIsValid(true);
            items.add(item);
        }
        return items;
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

    private String normalizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        if (name.contains("\\")) {
            name = name.replace("\\", "/");
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name.trim();
    }

    private String resolveIginxDataPath(DataItem item) {
        if (item == null) {
            return "";
        }

        String logicalPath = item.getLogicalPath();
        String fileName = normalizeFileName(item.getFileName());
        if (logicalPath != null && logicalPath.startsWith("/extern/")) {
            if (logicalPath.startsWith("/extern/filesystem/")) {
                String prefix = "/extern/filesystem/";
                String body = logicalPath.substring(prefix.length());
                String[] segs = body.isEmpty() ? new String[0] : body.split("/");
                StringBuilder fsBase = new StringBuilder("data.extern");
                for (String seg : segs) {
                    String cleaned = seg == null ? "" : seg.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
                    if (!cleaned.isEmpty()) {
                        fsBase.append('.').append(cleaned.replace(".", "\\\\."));
                    }
                }
                String base = fsBase.toString();
                if (!fileName.isEmpty()) {
                    return StorageUtils.toFileLeafPath(base, fileName);
                }
                return base;
            }

            String suffix = logicalPath.substring("/extern/".length());
            if (suffix.isEmpty()) {
                return "data.extern";
            }

            int slash = suffix.indexOf('/');
            String sourceKey = slash >= 0 ? suffix.substring(0, slash) : suffix;
            String externalBody = slash >= 0 ? suffix.substring(slash + 1) : "";

            String schemaPrefix;
            if (isDefaultExternalSourceKey(sourceKey)) {
                schemaPrefix = "data.extern";
            } else {
                schemaPrefix = "data.extern." + sanitizeExternalSourceKey(sourceKey);
            }

            if (isDefaultExternalSourceKey(sourceKey) && externalBody.startsWith(sourceKey + "/")) {
                externalBody = externalBody.substring(sourceKey.length() + 1);
            }

            // Split by / and escape dots in each segment
            String[] segments = externalBody.isEmpty() ? new String[0] : externalBody.split("/");
            StringBuilder pathBuilder = new StringBuilder(schemaPrefix);
            for (String seg : segments) {
                String cleaned = seg == null ? "" : seg.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
                if (!cleaned.isEmpty()) {
                    pathBuilder.append('.').append(cleaned.replace(".", "\\."));
                }
            }
            String basePath = pathBuilder.toString();
            
            if (!fileName.isEmpty()) {
                if (isFilesystemLikeSourceKey(sourceKey)) {
                    return StorageUtils.toFileLeafPath(basePath, fileName);
                }
                if (isStructuredExternalSourceKey(sourceKey)) {
                    return appendExternalLeafPath(basePath, fileName);
                }
            }
            return basePath;
        }

        String basePath = StorageUtils.toIginxDataPath(logicalPath);
        if (!fileName.isEmpty()) {
            return StorageUtils.toFileLeafPath(basePath, fileName);
        }
        return basePath;
    }

    private boolean isDefaultExternalSourceKey(String sourceKey) {
        String key = sourceKey == null ? "" : sourceKey.trim().toLowerCase(Locale.ROOT);
        return "filesystem".equals(key)
                || "mysql".equals(key)
                || "postgres".equals(key)
                || "iotdb".equals(key);
    }

    private boolean isFilesystemLikeSourceKey(String sourceKey) {
        String key = sourceKey == null ? "" : sourceKey.trim().toLowerCase(Locale.ROOT);
        return "filesystem".equals(key) || key.startsWith("filesystem");
    }

    private boolean isStructuredExternalSourceKey(String sourceKey) {
        String key = sourceKey == null ? "" : sourceKey.trim().toLowerCase(Locale.ROOT);
        return "mysql".equals(key) || key.startsWith("mysql")
                || "postgres".equals(key) || key.startsWith("postgres")
                || "iotdb".equals(key) || key.startsWith("iotdb");
    }

    private String appendExternalLeafPath(String basePath, String fileName) {
        String base = basePath == null ? "" : basePath.trim();
        String leaf = sanitizeExternalSourceKey(fileName);
        if (base.isEmpty() || leaf.isEmpty()) {
            return base;
        }

        int idx = StorageUtils.findLastUnescapedDot(base);
        String currentLeaf = idx >= 0 ? base.substring(idx + 1) : base;
        currentLeaf = StorageUtils.normalizeEscapedPath(currentLeaf);
        if (currentLeaf.equals(leaf)) {
            return base;
        }
        return base + "." + leaf;
    }

    private boolean isLegacyExternalStructuredItemInParent(DataItem item, String parentPath) {
        if (item == null || !isExternalStructuredDataItem(item)) {
            return false;
        }
        String name = normalizeFileName(item.getFileName());
        if (name.isEmpty()) {
            return false;
        }
        String expected = normalizePath(parentPath + "/" + name);
        return expected.equals(normalizePath(item.getLogicalPath()));
    }

    private boolean isExternalStructuredDataItem(DataItem item) {
        if (item == null) {
            return false;
        }
        String logicalPath = normalizePath(item.getLogicalPath());
        if (!logicalPath.startsWith("/extern/")) {
            return false;
        }
        String type = item.getDataType() == null ? "" : item.getDataType().trim().toLowerCase(Locale.ROOT);
        return "relational".equals(type) || "timeseries".equals(type) || "keyvalue".equals(type);
    }

    private String sanitizeExternalSourceKey(String sourceKey) {
        if (sourceKey == null) {
            return "";
        }
        return sourceKey.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getValueAsString(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        }
        if (obj instanceof java.nio.ByteBuffer) {
            return new String(StorageUtils.toByteArray(obj), StandardCharsets.UTF_8);
        }
        return obj.toString();
    }

    private Long getValueAsLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Integer) return ((Integer) obj).longValue();
        if (obj instanceof byte[]) {
            try { return Long.parseLong(new String((byte[]) obj)); }
            catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private Boolean getValueAsBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof byte[]) return Boolean.parseBoolean(new String((byte[]) obj));
        return false;
    }
}
