package com.storage.engine.service;

import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.DataItem;
import com.storage.engine.service.adapter.StorageAdapter;
import com.storage.engine.service.adapter.StorageAdapterFactory;
import com.storage.engine.service.adapter.StorageUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class StorageService {

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private StorageAdapterFactory adapterFactory;

    @Autowired
    private AccessService accessService;

    /**
     * Store a file to IGinX using the adapter pattern.
     * Rejects storage if the logical path is already occupied.
     */
    public synchronized DataItem storeData(MultipartFile file, String logicalPath, String dataType) throws Exception {
        // Normalize logical path
        logicalPath = normalizePath(logicalPath);

        // Reject root path
        if ("/".equals(logicalPath)) {
            throw new IllegalArgumentException("不能使用根路径 '/' 作为存储路径，请指定具体的逻辑路径（如 /project/data）。");
        }

        // ---- Path conflict checking ----
        // 1. Exact duplicate: same path already has data
        DataItem existing = accessService.getMetaByPath(logicalPath);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "逻辑路径 '" + logicalPath + "' 已被占用（文件: "
                    + existing.getFileName() + "，类型: " + existing.getDataType()
                    + "）。请使用其他路径或先删除已有数据。");
        }

        // 2. Hierarchy conflict: check all existing items
        java.util.List<DataItem> allMeta = accessService.getAllMeta();
        String newPathWithSlash = logicalPath + "/";
        for (DataItem item : allMeta) {
            String existingPath = item.getLogicalPath();
            if (existingPath == null) continue;
            // New path is a CHILD of an existing data item
            //   e.g., existing = /test/rel (data), new = /test/rel/two
            //   This would make /test/rel appear as a directory, hiding the original data.
            if (logicalPath.startsWith(existingPath + "/")) {
                throw new IllegalArgumentException(
                        "路径冲突：'" + existingPath + "' 已存储为"
                        + item.getDataType() + "类型数据（文件: " + item.getFileName()
                        + "）。不能在已有数据路径下创建子路径 '" + logicalPath + "'。");
            }
            // New path is a PARENT of an existing data item
            //   e.g., existing = /test/rel/two (data), new = /test/rel
            //   The new item would be immediately hidden by directory listing.
            if (existingPath.startsWith(newPathWithSlash)) {
                throw new IllegalArgumentException(
                        "路径冲突：路径 '" + logicalPath + "' 下已存在子数据 '"
                        + existingPath + "'。不能在已有子数据的父路径上直接存储文件。");
            }
        }

        // Get the appropriate adapter
        StorageAdapter adapter = adapterFactory.getAdapter(dataType);

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        String fileFormat = getFileExtension(fileName);
        String createTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Generate metadata ID
        long metaId = iginxDao.getMaxMetaId() + 1;

        // Store metadata
        iginxDao.insertMeta(metaId, logicalPath, dataType, fileName, fileSize, fileFormat, createTime);

        // Convert logical path to IGinX data path
        String iginxPath = StorageUtils.toIginxDataPath(logicalPath);

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

        return item;
    }

    // ==================== Utility Methods ====================

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
}
