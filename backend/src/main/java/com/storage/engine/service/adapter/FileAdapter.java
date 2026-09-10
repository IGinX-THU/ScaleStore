package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapter for arbitrary file data.
 */
@Component
public class FileAdapter implements StorageAdapter {

    private static final Logger logger = LoggerFactory.getLogger(FileAdapter.class);
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int FILESYSTEM_INFER_SCHEMA_ROW_BYTES = 4 * 1024;
    private static final int IMAGE_PREVIEW_BYTES = 5 * CHUNK_SIZE;
    private static final int FILE_PREVIEW_BYTES = CHUNK_SIZE;

    @Autowired
    private IGinxDao iginxDao;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_FILE;
    }

    @Override
    public List<String> getSupportedFormats() {
        return Collections.emptyList();
    }

    @Override
    public void store(MultipartFile file, String iginxPath) throws Exception {
        byte[] bytes = file.getBytes();
        int totalChunks = Math.max(1, (int) Math.ceil((double) bytes.length / CHUNK_SIZE));

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, bytes.length);
            byte[] chunk = Arrays.copyOfRange(bytes, start, end);

            List<String> paths = Collections.singletonList(iginxPath);
            long[] timestamps = new long[]{i};
            Object[] valuesList = new Object[]{new byte[][]{chunk}};
            List<DataType> types = Collections.singletonList(DataType.BINARY);

            iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
        }

        logger.info("File stored: path={}, size={}, chunks={}", iginxPath, bytes.length, totalChunks);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        boolean image = isImagePath(iginxPath);
        int maxPreviewBytes = image ? IMAGE_PREVIEW_BYTES : FILE_PREVIEW_BYTES;
        int previewRows = rowsForInferSchemaPreview(maxPreviewBytes);
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, previewRows);
        byte[] bytes = extractBytes(result, iginxPath, maxPreviewBytes);
        if (bytes.length == 0) {
            throw new RuntimeException("File preview failed: no content found, path=" + iginxPath);
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("base64", Base64.getEncoder().encodeToString(bytes));
        data.put("previewLimit", image ? "图片文件最多预览前5MB数据" : "文件数据最多预览前1MB数据");
        data.put("previewSize", Integer.valueOf(bytes.length));
        data.put("previewBytes", Integer.valueOf(maxPreviewBytes));
        data.put("previewRows", Integer.valueOf(previewRows));
        data.put("imagePreview", Boolean.valueOf(image));
        if (!image && isTextPath(iginxPath)) {
            data.put("textPreview", new String(bytes, StandardCharsets.UTF_8));
        }
        return data;
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, null);
        byte[] bytes = extractBytes(result, iginxPath, null);
        if (bytes.length == 0) {
            throw new RuntimeException("File download failed: no content found, path=" + iginxPath);
        }
        return bytes;
    }

    private byte[] extractBytes(SessionExecuteSqlResult result, String expectedPath, Integer maxBytes) {
        if (result == null || result.getValues() == null || result.getValues().isEmpty()) {
            return new byte[0];
        }

        List<String> paths = result.getPaths();
        if (paths == null || paths.size() != 1) {
            return new byte[0];
        }
        String resolvedPath = StorageUtils.normalizeEscapedPath(paths.get(0));
        String expected = StorageUtils.normalizeEscapedPath(expectedPath);
        if (!resolvedPath.equals(expected)) {
            logger.warn("File query returned unexpected path. expected={}, actual={}", expected, resolvedPath);
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (List<Object> row : result.getValues()) {
            if (row == null || row.size() != 1) {
                continue;
            }
            byte[] chunk = StorageUtils.toByteArray(row.get(0));
            if (maxBytes != null) {
                int remaining = maxBytes.intValue() - out.size();
                if (remaining <= 0) {
                    break;
                }
                out.write(chunk, 0, Math.min(chunk.length, remaining));
                if (out.size() >= maxBytes.intValue()) {
                    break;
                }
            } else {
                out.write(chunk, 0, chunk.length);
            }
        }
        return out.toByteArray();
    }

    private SessionExecuteSqlResult queryLeafFromParent(String fullPath, Integer limit) {
        String[] pair = StorageUtils.splitParentAndLeaf(fullPath);
        String parentPath = pair[0];
        String leafPath = pair[1];
        if (parentPath.isEmpty() || leafPath.isEmpty()) {
            throw new RuntimeException("Invalid file access path: " + fullPath);
        }

        String sql = "select "
                + StorageUtils.quoteIdentifierSegment(StorageUtils.normalizeEscapedPath(leafPath))
                + " from "
                + StorageUtils.quoteIdentifierPath(parentPath);
        if (limit != null && limit.intValue() > 0) {
            sql += " limit " + limit.intValue();
        }
        sql += ";";
        logger.info("[IGinX-SQL] {}", sql);
        return iginxDao.executeSql(sql);
    }

    private int rowsForInferSchemaPreview(int maxPreviewBytes) {
        return Math.max(1, (maxPreviewBytes + FILESYSTEM_INFER_SCHEMA_ROW_BYTES - 1) / FILESYSTEM_INFER_SCHEMA_ROW_BYTES);
    }

    private boolean isImagePath(String path) {
        String leaf = normalizedLeaf(path);
        return leaf.endsWith(".jpg")
                || leaf.endsWith(".jpeg")
                || leaf.endsWith(".png")
                || leaf.endsWith(".bmp")
                || leaf.endsWith(".gif")
                || leaf.endsWith(".webp");
    }

    private boolean isTextPath(String path) {
        String leaf = normalizedLeaf(path);
        return leaf.endsWith(".txt")
                || leaf.endsWith(".json")
                || leaf.endsWith(".xml")
                || leaf.endsWith(".csv")
                || leaf.endsWith(".tsv")
                || leaf.endsWith(".md")
                || leaf.endsWith(".properties")
                || leaf.endsWith(".env")
                || leaf.endsWith(".yaml")
                || leaf.endsWith(".yml")
                || leaf.endsWith(".log");
    }

    private String normalizedLeaf(String path) {
        String normalized = StorageUtils.normalizeEscapedPath(path).toLowerCase(Locale.ROOT);
        String leaf = normalized;
        int idx = StorageUtils.findLastUnescapedDot(normalized);
        if (idx >= 0 && idx < normalized.length() - 1) {
            leaf = normalized.substring(idx + 1);
        }
        return leaf.replace("\\.", ".");
    }
}
