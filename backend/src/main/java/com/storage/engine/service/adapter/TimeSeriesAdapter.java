package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.MetadataExtractResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for time-series data (CSV/TXT).
 * First column is the timestamp (key), remaining columns are values.
 * Supports mixed types: numeric columns stored as numbers, string columns stored as BINARY.
 * Uses SQL batch INSERT to avoid the primitive-array cast issue with insertColumnRecords.
 */
@Component
public class TimeSeriesAdapter implements StorageAdapter {

    private static final int BATCH_SIZE = 100;

    @Autowired
    private IGinxDao iginxDao;

    @Override
    public String getDataType() {
        return IGinxConstants.TYPE_TIMESERIES;
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
            throw new IllegalArgumentException("Time series data must have at least a header row and one data row");
        }

        String[] headers = rows.get(0);
        int numCols = headers.length - 1;
        if (numCols <= 0) {
            throw new IllegalArgumentException("Time series data must have at least one value column besides timestamp");
        }

        // Build sanitized column names
        String[] colNames = new String[numCols];
        for (int c = 0; c < numCols; c++) {
            colNames[c] = StorageUtils.sanitizeColumnName(headers[c + 1].trim());
        }

        // Parse data rows – keep raw string values for each cell
        List<Long> parsedTimestamps = new ArrayList<>();
        List<String[]> parsedStringValues = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 2) continue;
            try {
                long ts = StorageUtils.parseTimestamp(row[0].trim());
                String[] vals = new String[numCols];
                for (int j = 0; j < numCols; j++) {
                    vals[j] = (j + 1 < row.length) ? row[j + 1].trim() : "";
                }
                parsedTimestamps.add(ts);
                parsedStringValues.add(vals);
            } catch (Exception e) {
                // Skip rows with invalid timestamps
            }
        }

        if (parsedTimestamps.isEmpty()) {
            throw new IllegalArgumentException("No valid time series data rows found");
        }

        // Detect column types: a column is numeric if ALL values parse as double
        boolean[] isNumeric = new boolean[numCols];
        Arrays.fill(isNumeric, true);
        for (String[] vals : parsedStringValues) {
            for (int j = 0; j < numCols; j++) {
                if (isNumeric[j] && !vals[j].isEmpty()) {
                    try {
                        Double.parseDouble(vals[j]);
                    } catch (NumberFormatException e) {
                        isNumeric[j] = false;
                    }
                }
            }
        }

        // Use SQL batch INSERT.
        // Numeric columns: bare values.  String columns: single-quoted values.
        insertBatchSql(iginxPath, colNames, isNumeric, parsedTimestamps, parsedStringValues);
    }

    /**
     * Batch insert using SQL INSERT statements, supporting mixed numeric/string columns.
     */
    private void insertBatchSql(String iginxPath, String[] colNames, boolean[] isNumeric,
                                 List<Long> timestamps, List<String[]> values) {
        int totalRows = timestamps.size();
        for (int start = 0; start < totalRows; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, totalRows);

            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(iginxPath).append("(key");
            for (String col : colNames) {
                sql.append(", ").append(col);
            }
            sql.append(") VALUES ");

            for (int i = start; i < end; i++) {
                if (i > start) sql.append(", ");
                sql.append("(").append(timestamps.get(i));
                String[] vals = values.get(i);
                for (int j = 0; j < vals.length; j++) {
                    sql.append(", ");
                    if (isNumeric[j]) {
                        // Numeric column – output bare value (or 0.0 if empty)
                        if (vals[j].isEmpty()) {
                            sql.append("0.0");
                        } else {
                            sql.append(Double.parseDouble(vals[j]));
                        }
                    } else {
                        // String column – wrap in single quotes
                        sql.append("'").append(StorageUtils.escapeSqlValue(vals[j])).append("'");
                    }
                }
                sql.append(")");
            }
            sql.append(";");

            iginxDao.executeSql(sql.toString());
        }
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
        if (rows.isEmpty()) {
            return result;
        }

        result.setFieldKind("column");
        String[] headers = rows.get(0);
        List<String> fields = new ArrayList<String>();
        for (String header : headers) {
            String field = header == null ? "" : header.trim();
            if (!field.isEmpty()) {
                fields.add(field);
            }
        }
        result.setFields(fields);
        return result;
    }

    // ==================== Helpers ====================

    private Map<String, Object> buildTablePreview(SessionExecuteSqlResult result) {
        Map<String, Object> preview = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

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
                row.add(keys[i]);
                List<Object> valueRow = values.get(i);
                for (Object val : valueRow) {
                    row.add(StorageUtils.convertValue(val));
                }
                rows.add(row);
            }
        }

        List<String> allColumns = new ArrayList<>();
        allColumns.add("key");
        allColumns.addAll(columns);

        preview.put("columns", allColumns);
        preview.put("rows", rows);
        preview.put("totalRows", keys != null ? keys.length : 0);
        return preview;
    }

    private byte[] reconstructCsv(SessionExecuteSqlResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> paths = result.getPaths();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();

        sb.append("key");
        for (String path : paths) {
            sb.append(",");
            int lastDot = path.lastIndexOf('.');
            sb.append(lastDot >= 0 ? path.substring(lastDot + 1) : path);
        }
        sb.append("\n");

        if (keys != null) {
            for (int i = 0; i < keys.length; i++) {
                sb.append(keys[i]);
                List<Object> row = values.get(i);
                for (Object val : row) {
                    sb.append(",");
                    sb.append(StorageUtils.convertValueToString(val));
                }
                sb.append("\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
