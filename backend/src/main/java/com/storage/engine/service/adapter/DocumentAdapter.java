package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
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

        List<String> paths = Collections.singletonList(iginxPath + ".content");
        long[] timestamps = new long[]{0};
        Object[] valuesList = new Object[]{new byte[][]{contentBytes}};
        List<DataType> types = Collections.singletonList(DataType.BINARY);

        iginxDao.insertColumnRecords(paths, timestamps, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);

        List<String> paths = result.getPaths();
        List<List<Object>> values = result.getValues();

        if (values != null && !values.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("content")) {
                    Object val = values.get(0).get(i);
                    if (val instanceof byte[]) {
                        return new String((byte[]) val, StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return "";
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
