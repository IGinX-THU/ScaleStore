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

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for document data (JSON/XML).
 * Stores raw text as a single BINARY entry at path.content.
 */
@Component
public class DocumentAdapter implements StorageAdapter {

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private LlmService llmService;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_DOCUMENT;
    }

    @Override
    public List<String> getSupportedFormats() {
        return Arrays.asList("json", "xml");
    }

    @Override
    public void store(MultipartFile file, String iginxPath) throws Exception {
        byte[] contentBytes = file.getBytes();

        List<String> paths = Collections.singletonList(iginxPath);
        long[] timestamps = new long[]{0};
        Object[] valuesList = new Object[]{new byte[][]{contentBytes}};
        List<DataType> types = Collections.singletonList(DataType.BINARY);

        iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, Math.max(limit, 1));

        byte[] bytes = extractPrimaryBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("文档预览失败: 未查询到文档内容, path=" + iginxPath);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, null);
        byte[] bytes = extractPrimaryBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("文档下载失败: 未查询到文档内容, path=" + iginxPath);
        }
        return bytes;
    }

    @Override
    public MetadataExtractResult extractMetadata(byte[] fileBytes, String fileFormat) throws Exception {
        MetadataExtractResult result = new MetadataExtractResult();
        if (fileBytes == null || fileBytes.length == 0) {
            return result;
        }

        String text = new String(fileBytes, StandardCharsets.UTF_8);

        LlmService.ExtractResult llm = llmService.extractSemanticTriplesFromTextStrict("文档", text);
        result.setEntities(llm.getEntities());
        result.setTriples(llm.getTriples());
        result.setLlmUsed(llm.isLlmUsed());
        result.setLlmResponse(llm.getRawResponse());
        result.setLlmError(llm.getError());
        return result;
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
            throw new RuntimeException("文档访问路径非法: " + fullPath);
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
