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
 * Adapter for relational / tabular data (CSV/TXT).
 * Uses sequential row indices as keys, stores all values as BINARY (string).
 */
@Component
public class RelationalAdapter implements StorageAdapter {

    @Autowired
    private IGinxDao iginxDao;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_RELATIONAL;
    }

    @Override
    public List<String> getSupportedFormats() {
        return Arrays.asList("csv", "txt");
    }

    @Override
    public void store(MultipartFile file, String iginxPath) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        List<String[]> rows = StorageUtils.parseCsv(content);
        if (rows.size() < 2) {
            throw new IllegalArgumentException("Relational data must have at least a header row and one data row");
        }

        String[] headers = rows.get(0);
        int numCols = headers.length;
        int numRows = rows.size() - 1;

        long[] keys = new long[numRows];
        for (int i = 0; i < numRows; i++) {
            keys[i] = i;
        }

        // Build column-oriented data – all columns as BINARY (string)
        List<String> paths = new ArrayList<>();
        List<DataType> types = new ArrayList<>();
        Object[] valuesList = new Object[numCols];

        for (int col = 0; col < numCols; col++) {
            String colName = StorageUtils.sanitizeColumnName(headers[col].trim());
            paths.add(iginxPath + "." + colName);
            types.add(DataType.BINARY);

            byte[][] colValues = new byte[numRows][];
            for (int row = 0; row < numRows; row++) {
                String[] dataRow = rows.get(row + 1);
                String val = (col < dataRow.length) ? dataRow[col].trim() : "";
                colValues[row] = val.getBytes(StandardCharsets.UTF_8);
            }
            valuesList[col] = colValues;
        }

        iginxDao.insertColumnRecords(paths, keys, valuesList, types);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPathWithLimit(iginxPath, limit);
        return buildTablePreview(result);
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);
        return reconstructCsv(result);
    }

    @Override
    public MetadataExtractResult extractMetadata(byte[] fileBytes, String fileFormat) throws Exception {
        MetadataExtractResult result = new MetadataExtractResult();
        if (fileBytes == null || fileBytes.length == 0) {
            return result;
        }

        String text = new String(fileBytes, StandardCharsets.UTF_8);
        List<String[]> rows = StorageUtils.parseCsv(text);
        if (!rows.isEmpty()) {
            result.setFieldKind("column");
            result.setFields(Arrays.asList(rows.get(0)));
        }
        return result;
    }

    // ==================== Helpers ====================

    private Map<String, Object> buildTablePreview(SessionExecuteSqlResult result) {
        Map<String, Object> preview = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        List<List<Object>> tableRows = new ArrayList<>();

        List<String> paths = result.getPaths();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();

        for (String path : paths) {
            int lastDot = path.lastIndexOf('.');
            columns.add(lastDot >= 0 ? path.substring(lastDot + 1) : path);
        }

        if (keys != null) {
            for (int i = 0; i < keys.length; i++) {
                List<Object> row = new ArrayList<>();
                // Skip key column – it's just a row index, not part of the original data
                List<Object> valueRow = values.get(i);
                for (Object val : valueRow) {
                    row.add(StorageUtils.convertValue(val));
                }
                tableRows.add(row);
            }
        }

        preview.put("columns", columns);
        preview.put("rows", tableRows);
        preview.put("totalRows", keys != null ? keys.length : 0);
        return preview;
    }

    private byte[] reconstructCsv(SessionExecuteSqlResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> paths = result.getPaths();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();

        for (int p = 0; p < paths.size(); p++) {
            if (p > 0) sb.append(",");
            int lastDot = paths.get(p).lastIndexOf('.');
            sb.append(lastDot >= 0 ? paths.get(p).substring(lastDot + 1) : paths.get(p));
        }
        sb.append("\n");

        if (keys != null) {
            for (int i = 0; i < keys.length; i++) {
                List<Object> row = values.get(i);
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append(StorageUtils.convertValueToString(row.get(j)));
                }
                sb.append("\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
