package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.MetadataExtractResult;
import com.storage.engine.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.*;

/**
 * Adapter for image data (JPG/PNG/BMP).
 * Stores raw bytes as a single BINARY entry at path.content.
 */
@Component
public class ImageAdapter implements StorageAdapter {

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private LlmService llmService;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_IMAGE;
    }

    @Override
    public List<String> getSupportedFormats() {
        return Arrays.asList("jpg", "jpeg", "png", "bmp");
    }

    @Override
    public void store(MultipartFile file, String iginxPath) throws Exception {
        byte[] imageBytes = file.getBytes();

        List<String> paths = Collections.singletonList(iginxPath);
        long[] timestamps = new long[]{0};
        Object[] valuesList = new Object[]{new byte[][]{imageBytes}};
        List<DataType> types = Collections.singletonList(DataType.BINARY);

        iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, Math.max(limit, 1));

        Map<String, Object> data = new HashMap<>();
        byte[] bytes = extractPrimaryBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("图像预览失败: 未查询到图像内容, path=" + iginxPath);
        }
        data.put("base64", Base64.getEncoder().encodeToString(bytes));
        return data;
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, null);
        byte[] bytes = extractPrimaryBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("图像下载失败: 未查询到图像内容, path=" + iginxPath);
        }
        return bytes;
    }

    private String mimeByFormat(String format) {
        if (format == null) return "image/png";
        String f = format.toLowerCase(Locale.ROOT);
        if ("jpg".equals(f) || "jpeg".equals(f)) return "image/jpeg";
        if ("bmp".equals(f)) return "image/bmp";
        return "image/png";
    }

    private byte[] extractPrimaryBytes(SessionExecuteSqlResult result, String expectedPath) {
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
            return new byte[0];
        }

        List<Object> firstRow = result.getValues().get(0);
        if (firstRow == null || firstRow.size() != 1) {
            return new byte[0];
        }
        return StorageUtils.toByteArray(firstRow.get(0));
    }

    private SessionExecuteSqlResult queryLeafFromParent(String fullPath, Integer limit) {
        String[] pair = StorageUtils.splitParentAndLeaf(fullPath);
        String parentPath = pair[0];
        String leafPath = pair[1];
        if (parentPath.isEmpty() || leafPath.isEmpty()) {
            throw new RuntimeException("图像访问路径非法: " + fullPath);
        }

        String leaf = StorageUtils.normalizeEscapedPath(leafPath);
        String sql = "select " + leaf + " from " + parentPath;
        if (limit != null && limit.intValue() > 0) {
            sql += " limit " + limit.intValue();
        }
        sql += ";";
        return iginxDao.executeSql(sql);
    }
}
