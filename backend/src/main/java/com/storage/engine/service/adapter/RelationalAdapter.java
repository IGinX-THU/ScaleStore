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

    // ==================== Helpers ====================

    private Map<String, Object> buildTablePreview(SessionExecuteSqlResult result) {
        Map<String, Object> preview = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        List<List<Object>> tableRows = new ArrayList<>();

        if (result == null) {
            preview.put("columns", columns);
            preview.put("rows", tableRows);
            preview.put("totalRows", 0);
            return preview;
        }

        List<String> paths = result.getPaths() == null ? Collections.<String>emptyList() : result.getPaths();
        List<List<Object>> values = result.getValues() == null ? Collections.<List<Object>>emptyList() : result.getValues();

        int valueStart = 0;
        if (!paths.isEmpty() && isKeyColumnName(extractColumnName(paths.get(0)))) {
            valueStart = 1;
        }

        for (int i = valueStart; i < paths.size(); i++) {
            columns.add(extractColumnName(paths.get(i)));
        }

        for (List<Object> valueRow : values) {
            if (valueRow == null) {
                continue;
            }
            List<Object> row = new ArrayList<Object>();
            for (int j = valueStart; j < valueRow.size(); j++) {
                row.add(StorageUtils.convertValue(valueRow.get(j)));
            }
            tableRows.add(row);
        }

        preview.put("columns", columns);
        preview.put("rows", tableRows);
        preview.put("totalRows", tableRows.size());
        return preview;
    }

    private byte[] reconstructCsv(SessionExecuteSqlResult result) {
        StringBuilder sb = new StringBuilder();
        if (result == null) {
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        List<String> paths = result.getPaths() == null ? Collections.<String>emptyList() : result.getPaths();
        List<List<Object>> values = result.getValues() == null ? Collections.<List<Object>>emptyList() : result.getValues();

        int valueStart = 0;
        if (!paths.isEmpty() && isKeyColumnName(extractColumnName(paths.get(0)))) {
            valueStart = 1;
        }

        for (int p = valueStart; p < paths.size(); p++) {
            if (p > valueStart) sb.append(",");
            sb.append(extractColumnName(paths.get(p)));
        }
        sb.append("\n");

        for (List<Object> row : values) {
            if (row == null) {
                continue;
            }
            for (int j = valueStart; j < row.size(); j++) {
                if (j > valueStart) sb.append(",");
                sb.append(StorageUtils.convertValueToString(row.get(j)));
            }
            sb.append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String extractColumnName(String path) {
        if (path == null) {
            return "";
        }
        int lastDot = path.lastIndexOf('.');
        String token = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        return token.replace("\\\\.", ".").replace("\\.", ".");
    }

    private boolean isKeyColumnName(String name) {
        return "key".equalsIgnoreCase(name == null ? "" : name.trim());
    }
}
