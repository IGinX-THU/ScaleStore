package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(DocumentAdapter.class);

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
        
        // 每1MB存储一行，类似IGinX filesystem数据源的存储方式
        int chunkSize = 1024 * 1024; // 1MB
        int totalChunks = (int) Math.ceil((double) contentBytes.length / chunkSize);
        
        // 将文件分块存储，时间戳从0开始递增
        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, contentBytes.length);
            byte[] chunk = Arrays.copyOfRange(contentBytes, start, end);
            
            List<String> paths = Collections.singletonList(iginxPath);
            long[] timestamps = new long[]{i};
            Object[] valuesList = new Object[]{new byte[][]{chunk}};
            List<DataType> types = Collections.singletonList(DataType.BINARY);
            
            iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
        }
        
        logger.info("文档存储完成: path={}, 总大小={}字节, 分块数={}", iginxPath, contentBytes.length, totalChunks);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        // 文档数据预览只显示前1MB内容（1个分块）
        int previewChunks = 1;
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, previewChunks);

        byte[] bytes = extractMultiChunkBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("文档预览失败: 未查询到文档内容, path=" + iginxPath);
        }
        
        logger.info("文档预览大小: {}字节，预览限制: 1MB", bytes.length);
        
        // 返回字符串内容，前端会自行处理
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        // 下载时读取所有分块数据
        SessionExecuteSqlResult result = queryLeafFromParent(iginxPath, null);
        byte[] bytes = extractMultiChunkBytes(result, iginxPath);
        if (bytes.length == 0) {
            throw new RuntimeException("文档下载失败: 未查询到文档内容, path=" + iginxPath);
        }
        
        logger.info("文档下载完成: path={}, 总大小={}字节", iginxPath, bytes.length);
        return bytes;
    }

    /**
     * 从查询结果中提取多个分块的字节数据并拼接
     */
    private byte[] extractMultiChunkBytes(SessionExecuteSqlResult result, String expectedPath) {
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

        // 获取所有行的数据并拼接
        List<List<Object>> values = result.getValues();
        List<byte[]> chunks = new ArrayList<>();
        int totalSize = 0;
        
        for (List<Object> row : values) {
            if (row != null && row.size() == 1) {
                byte[] chunk = StorageUtils.toByteArray(row.get(0));
                chunks.add(chunk);
                totalSize += chunk.length;
            }
        }
        
        // 拼接所有分块
        byte[] combined = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, combined, offset, chunk.length);
            offset += chunk.length;
        }
        
        return combined;
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
        String quotedLeaf = StorageUtils.quoteIdentifierSegment(leaf);
        String quotedParent = StorageUtils.quoteIdentifierPath(parentPath);
        String sql = "select " + quotedLeaf + " from " + quotedParent;
        if (limit != null && limit.intValue() > 0) {
            sql += " limit " + limit.intValue();
        }
        sql += ";";
        logger.info("[IGinX-SQL] {}", sql);
        return iginxDao.executeSql(sql);
    }
}
