package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.MetadataExtractResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for key-value data (.properties/.env).
 * Parses flat key-value pairs; each key becomes a column stored as BINARY.
 */
@Component
public class KeyValueAdapter implements StorageAdapter {

    @Autowired
    private IGinxDao iginxDao;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_KEYVALUE;
    }

    @Override
    public List<String> getSupportedFormats() {
        return Arrays.asList("properties", "env");
    }

    @Override
    public void store(MultipartFile file, String iginxPath) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String format = StorageUtils.getFileExtension(file.getOriginalFilename());

        Map<String, String> kvPairs = StorageUtils.parseKeyValueContent(content, format);

        if (kvPairs.isEmpty()) {
            throw new IllegalArgumentException("No valid key-value pairs found in file");
        }

        List<String> paths = new ArrayList<>();
        List<DataType> types = new ArrayList<>();
        List<byte[][]> values = new ArrayList<>();

        for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
            String key = StorageUtils.sanitizeColumnName(entry.getKey());
            paths.add(iginxPath + "." + key);
            types.add(DataType.BINARY);
            values.add(new byte[][]{entry.getValue().getBytes(StandardCharsets.UTF_8)});
        }

        long[] timestamps = new long[]{0};
        Object[] valuesList = values.toArray();

        iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);

        Map<String, String> kvData = new LinkedHashMap<>();
        List<String> paths = result.getPaths();
        List<List<Object>> values = result.getValues();

        if (values != null && !values.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                String key = path.substring(path.lastIndexOf('.') + 1);
                Object val = values.get(0).get(i);
                kvData.put(key, StorageUtils.convertValueToString(val));
            }
        }
        return kvData;
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> kvData = (Map<String, String>) getPreviewData(iginxPath, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int count = 0;
        for (Map.Entry<String, String> entry : kvData.entrySet()) {
            if (count > 0) sb.append(",\n");
            sb.append("  \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
            count++;
        }
        sb.append("\n}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

}
