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

        List<String> paths = Collections.singletonList(iginxPath + ".content");
        long[] timestamps = new long[]{0};
        Object[] valuesList = new Object[]{new byte[][]{imageBytes}};
        List<DataType> types = Collections.singletonList(DataType.BINARY);

        iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);

        Map<String, Object> data = new HashMap<>();
        List<String> paths = result.getPaths();
        List<List<Object>> values = result.getValues();

        if (values != null && !values.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("content")) {
                    Object val = values.get(0).get(i);
                    if (val instanceof byte[]) {
                        data.put("base64", Base64.getEncoder().encodeToString((byte[]) val));
                    }
                }
            }
        }
        return data;
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);

        List<String> paths = result.getPaths();
        List<List<Object>> values = result.getValues();

        if (values != null && !values.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("content")) {
                    Object val = values.get(0).get(i);
                    if (val instanceof byte[]) {
                        return (byte[]) val;
                    }
                }
            }
        }
        return new byte[0];
    }

    @Override
    public MetadataExtractResult extractMetadata(byte[] fileBytes, String fileFormat) throws Exception {
        MetadataExtractResult result = new MetadataExtractResult();
        if (fileBytes == null || fileBytes.length == 0) {
            return result;
        }

        String mimeType = mimeByFormat(fileFormat);
        LlmService.ExtractResult llm = llmService.extractSemanticTriplesFromImageStrict(mimeType, fileBytes);
        result.setEntities(llm.getEntities());
        result.setTriples(llm.getTriples());
        result.setLlmUsed(llm.isLlmUsed());
        result.setLlmResponse(llm.getRawResponse());
        result.setLlmError(llm.getError());
        return result;
    }

    private String mimeByFormat(String format) {
        if (format == null) return "image/png";
        String f = format.toLowerCase(Locale.ROOT);
        if ("jpg".equals(f) || "jpeg".equals(f)) return "image/jpeg";
        if ("bmp".equals(f)) return "image/bmp";
        return "image/png";
    }
}
