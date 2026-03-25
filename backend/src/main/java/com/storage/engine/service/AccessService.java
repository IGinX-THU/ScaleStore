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
                if (logicalPath.equals(item.getLogicalPath())) {
                    return item;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Access data by logical path.
     * - Exact match → returns metadata + preview from the adapter.
     * - No exact match, but is a prefix of children → returns a "directory" listing.
     * - Nothing found → returns null.
     */
    public DataItem accessData(String logicalPath) {
        logicalPath = normalizePath(logicalPath);

        // 1. Try exact match
        DataItem meta = getMetaByPath(logicalPath);
        if (meta != null) {
            String iginxPath = com.storage.engine.service.adapter.StorageUtils.toIginxDataPath(meta.getLogicalPath());
            String dataType = meta.getDataType();

            try {
                StorageAdapter adapter = adapterFactory.getAdapter(dataType);
                Object previewData = adapter.getPreviewData(iginxPath, 100);
                meta.setPreviewData(previewData);
            } catch (Exception e) {
                e.printStackTrace();
                meta.setPreviewData("Error loading data: " + e.getMessage());
            }
            return meta;
        }

        // 2. Issue 4: Directory listing — check for children under this path prefix
        List<DataItem> allMeta = getAllMeta();
        String prefix = logicalPath.endsWith("/") ? logicalPath : logicalPath + "/";
        List<Map<String, Object>> children = new ArrayList<>();

        for (DataItem item : allMeta) {
            String itemPath = item.getLogicalPath();
            if (itemPath != null && itemPath.startsWith(prefix)) {
                // Find the immediate child name (next segment after prefix)
                String remainder = itemPath.substring(prefix.length());
                String childName = remainder.contains("/") ? remainder.substring(0, remainder.indexOf('/')) : remainder;
                String childFullPath = prefix + childName;

                // Avoid duplicates: check if already added
                boolean alreadyAdded = false;
                for (Map<String, Object> c : children) {
                    if (childFullPath.equals(c.get("fullPath"))) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    child.put("name", childName);
                    child.put("fullPath", childFullPath);
                    // If the childFullPath is an exact data item, show its type; otherwise mark as directory
                    boolean isExact = childFullPath.equals(itemPath);
                    child.put("dataType", isExact ? item.getDataType() : "directory");
                    if (isExact) {
                        child.put("fileName", item.getFileName());
                        child.put("createTime", item.getCreateTime());
                    }
                    children.add(child);
                }
            }
        }

        if (!children.isEmpty()) {
            DataItem dirItem = new DataItem();
            dirItem.setLogicalPath(logicalPath);
            dirItem.setDataType("directory");
            dirItem.setPreviewData(children);
            return dirItem;
        }

        // 3. Nothing found
        return null;
    }

    /**
     * Get raw data bytes for download (delegates to the adapter).
     */
    public byte[] downloadData(String logicalPath) {
        DataItem meta = getMetaByPath(logicalPath);
        if (meta == null) return null;

        String iginxPath = StorageUtils.toIginxDataPath(meta.getLogicalPath());
        String dataType = meta.getDataType();

        try {
            StorageAdapter adapter = adapterFactory.getAdapter(dataType);
            return adapter.getDownloadBytes(iginxPath);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ==================== Parse Helpers ====================

    private List<DataItem> parseMeta(SessionExecuteSqlResult result) {
        List<DataItem> items = new ArrayList<>();
        if (result == null) return items;

        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        if (keys == null || values == null || paths == null) return items;

        int pathIdx = -1, typeIdx = -1, nameIdx = -1, sizeIdx = -1, formatIdx = -1, timeIdx = -1, validIdx = -1, knowledgeStatusIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("logicalPath")) pathIdx = i;
            else if (path.endsWith("dataType")) typeIdx = i;
            else if (path.endsWith("fileName")) nameIdx = i;
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

    private String getValueAsString(Object obj) {
        return obj == null ? null : (obj instanceof byte[] ? new String((byte[]) obj, StandardCharsets.UTF_8) : obj.toString());
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
