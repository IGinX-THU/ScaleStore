package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Adapter for image data (JPG/PNG/BMP).
 * Stores raw bytes as a single BINARY entry at path.content.
 */
@Component
public class ImageAdapter implements StorageAdapter {

    @Autowired
    private IGinxDao iginxDao;

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
}
