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

    // ==================== Helpers ====================

    private Map<String, Object> buildTablePreview(SessionExecuteSqlResult result) {
        Map<String, Object> preview = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        if (result == null) {
            preview.put("columns", columns);
            preview.put("rows", rows);
            preview.put("totalRows", 0);
            return preview;
        }

        List<String> paths = result.getPaths() == null ? Collections.<String>emptyList() : result.getPaths();
        List<List<Object>> values = result.getValues() == null ? Collections.<List<Object>>emptyList() : result.getValues();
        long[] keys = result.getKeys();

        boolean hasStandaloneKeys = keys != null && keys.length == values.size() && keys.length > 0;
        boolean firstPathIsKey = !paths.isEmpty() && isKeyColumnName(extractColumnName(paths.get(0)));

        columns.add("key");
        if (firstPathIsKey) {
            for (int i = 1; i < paths.size(); i++) {
                columns.add(extractColumnName(paths.get(i)));
            }
        } else {
            for (String path : paths) {
                columns.add(extractColumnName(path));
            }
        }

        for (int i = 0; i < values.size(); i++) {
            List<Object> valueRow = values.get(i);
            if (valueRow == null) {
                continue;
            }

            List<Object> row = new ArrayList<Object>();
            int valueStart = 0;

            if (hasStandaloneKeys) {
                row.add(keys[i]);
                if (firstPathIsKey) {
                    valueStart = 1;
                }
            } else if (firstPathIsKey) {
                row.add(StorageUtils.convertValue(valueRow.isEmpty() ? null : valueRow.get(0)));
                valueStart = 1;
            } else if (valueRow.size() == paths.size() + 1) {
                row.add(StorageUtils.convertValue(valueRow.get(0)));
                valueStart = 1;
            } else {
                row.add(i);
            }

            for (int j = valueStart; j < valueRow.size(); j++) {
                row.add(StorageUtils.convertValue(valueRow.get(j)));
            }
            rows.add(row);
        }

        preview.put("columns", columns);
        preview.put("rows", rows);
        preview.put("totalRows", rows.size());
        return preview;
    }

    private byte[] reconstructCsv(SessionExecuteSqlResult result) {
        StringBuilder sb = new StringBuilder();
        if (result == null) {
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        List<String> paths = result.getPaths() == null ? Collections.<String>emptyList() : result.getPaths();
        List<List<Object>> values = result.getValues() == null ? Collections.<List<Object>>emptyList() : result.getValues();
        long[] keys = result.getKeys();

        boolean hasStandaloneKeys = keys != null && keys.length == values.size() && keys.length > 0;
        boolean firstPathIsKey = !paths.isEmpty() && isKeyColumnName(extractColumnName(paths.get(0)));

        sb.append("key");
        int startCol = firstPathIsKey ? 1 : 0;
        for (int p = startCol; p < paths.size(); p++) {
            sb.append(",");
            sb.append(StorageUtils.escapeCsvCell(extractColumnName(paths.get(p))));
        }
        sb.append("\n");

        for (int i = 0; i < values.size(); i++) {
            List<Object> row = values.get(i);
            if (row == null) {
                continue;
            }

            int valueStart = 0;
            if (hasStandaloneKeys) {
                sb.append(StorageUtils.escapeCsvCell(keys[i]));
                if (firstPathIsKey) {
                    valueStart = 1;
                }
            } else if (firstPathIsKey) {
                sb.append(StorageUtils.escapeCsvCell(StorageUtils.convertValueToString(row.isEmpty() ? null : row.get(0))));
                valueStart = 1;
            } else if (row.size() == paths.size() + 1) {
                sb.append(StorageUtils.escapeCsvCell(StorageUtils.convertValueToString(row.get(0))));
                valueStart = 1;
            } else {
                sb.append(StorageUtils.escapeCsvCell(i));
            }

            for (int j = valueStart; j < row.size(); j++) {
                sb.append(",");
                sb.append(StorageUtils.escapeCsvCell(StorageUtils.convertValueToString(row.get(j))));
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
