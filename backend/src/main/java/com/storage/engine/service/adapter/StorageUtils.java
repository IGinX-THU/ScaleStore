package com.storage.engine.service.adapter;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared utility methods used by multiple storage adapters.
 */
public final class StorageUtils {

    private StorageUtils() {}

    /**
     * Parse CSV/TXT content into rows of string arrays.
     */
    public static List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<>();
        String delimiter = detectDelimiter(content);
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            rows.add(line.split(delimiter, -1));
        }
        return rows;
    }

    /**
     * Detect CSV delimiter (comma, tab, semicolon).
     */
    public static String detectDelimiter(String content) {
        String firstLine = content.split("\\r?\\n")[0];
        int commas = countChar(firstLine, ',');
        int tabs = countChar(firstLine, '\t');
        int semicolons = countChar(firstLine, ';');
        if (tabs >= commas && tabs >= semicolons && tabs > 0) return "\t";
        if (semicolons > commas && semicolons > 0) return ";";
        return ",";
    }

    public static int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    /**
     * Sanitize a column name for use as an IGinX path segment.
     */
    public static String sanitizeColumnName(String name) {
        if (name == null || name.isEmpty()) return "col";
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "c_" + sanitized;
        }
        return sanitized;
    }

    /**
     * Parse timestamp from string. Supports unix millis, ISO datetime, date-only.
     */
    public static long parseTimestamp(String ts) {
        // Try as long (unix timestamp in millis)
        try {
            return Long.parseLong(ts);
        } catch (NumberFormatException e) {
            // ignore
        }
        // Try as ISO datetime
        try {
            String normalized = ts.replace("T", " ").replace("/", "-");
            if (normalized.contains("+") || normalized.endsWith("Z")) {
                normalized = normalized.replaceAll("[+Z].*$", "");
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime dt;
            try {
                dt = LocalDateTime.parse(normalized.trim(), formatter);
            } catch (Exception e2) {
                DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                dt = LocalDateTime.parse(normalized.trim(), formatter2);
            }
            return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            // ignore
        }
        // Try as date only
        try {
            String normalized = ts.replace("/", "-");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            java.time.LocalDate ld = java.time.LocalDate.parse(normalized.trim(), formatter);
            return ld.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            // ignore
        }
        throw new IllegalArgumentException("Cannot parse timestamp: " + ts);
    }

    /**
     * Convert logical path (e.g., /project/sensor) to IGinX data path (e.g., data.project.sensor)
     */
    public static String toIginxDataPath(String logicalPath) {
        String path = logicalPath;
        if (path.startsWith("/")) path = path.substring(1);
        path = path.replace("/", ".");
        path = path.replaceAll("[^a-zA-Z0-9._]", "_");
        return com.storage.engine.constant.IGinxConstants.DATA_PATH_PREFIX + "." + path;
    }

    /**
     * Build a filesystem-style leaf path under a logical IGinX prefix.
     * Example: data.project.asset + file.txt -> data.project.asset.file\\.txt
     */
    public static String toFileLeafPath(String iginxPath, String originalFileName) {
        String base = originalFileName == null ? "" : originalFileName.trim();
        if (base.contains("\\")) {
            base = base.replace("\\", "/");
        }
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "content.bin";
        }
        return iginxPath + "." + base.replace(".", "\\.");
    }

    /**
     * Find last unescaped dot in an IGinX path.
     * A dot is considered escaped when it is immediately preceded by one or more backslashes.
     */
    public static int findLastUnescapedDot(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) != '.') {
                continue;
            }
            if (i > 0 && text.charAt(i - 1) == '\\') {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Normalize SQL-escaped path text to a canonical in-memory form.
     * Example: data.a.b\\.csv -> data.a.b\.csv
     */
    public static String normalizeEscapedPath(String path) {
        String normalized = path == null ? "" : path.trim();
        while (normalized.contains("\\\\")) {
            normalized = normalized.replace("\\\\", "\\");
        }
        return normalized;
    }

    /**
     * Split full path into parent path and leaf field token.
     */
    public static String[] splitParentAndLeaf(String fullPath) {
        String path = normalizeEscapedPath(fullPath);
        int idx = findLastUnescapedDot(path);
        if (idx <= 0 || idx >= path.length() - 1) {
            return new String[]{"", ""};
        }
        return new String[]{path.substring(0, idx), path.substring(idx + 1)};
    }

    /**
     * Get file extension from filename.
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) return "";
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * Convert an IGinX query value to display string.
     */
    public static Object convertValue(Object val) {
        if (val == null) return null;
        if (val instanceof byte[]) return new String((byte[]) val, StandardCharsets.UTF_8);
        if (val instanceof ByteBuffer) return new String(toByteArray(val), StandardCharsets.UTF_8);
        return val;
    }

    /**
     * Convert value to string for CSV/display.
     */
    public static String convertValueToString(Object val) {
        if (val == null) return "";
        if (val instanceof byte[]) return new String((byte[]) val, StandardCharsets.UTF_8);
        if (val instanceof ByteBuffer) return new String(toByteArray(val), StandardCharsets.UTF_8);
        return val.toString();
    }

    /**
     * Convert an IGinX query value into bytes.
     */
    public static byte[] toByteArray(Object value) {
        if (value == null) {
            return new byte[0];
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof ByteBuffer) {
            ByteBuffer buf = ((ByteBuffer) value).duplicate();
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Escape a value for use in IGinX SQL INSERT. Doubles single-quotes.
     */
    public static String escapeSqlValue(String value) {
        if (value == null) return "";
        return value.replace("'", "\\'");
    }

    // ==================== JSON / YAML Parsing for Key-Value ====================

    /**
     * Parse flat key-value content from JSON or YAML format.
     */
    public static Map<String, String> parseKeyValueContent(String content, String format) {
        Map<String, String> result = new LinkedHashMap<>();
        content = content.trim();

        if ("json".equalsIgnoreCase(format)) {
            parseJsonKV(content, "", result);
        } else if ("yaml".equalsIgnoreCase(format) || "yml".equalsIgnoreCase(format)) {
            parseYamlKV(content, result);
        } else {
            if (content.startsWith("{")) {
                parseJsonKV(content, "", result);
            } else {
                parseYamlKV(content, result);
            }
        }
        return result;
    }

    /**
     * Simple flat JSON key-value parser. Handles nested objects by flattening keys with dots.
     */
    public static void parseJsonKV(String json, String prefix, Map<String, String> result) {
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return;
        json = json.substring(1, json.length() - 1).trim();

        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;

            if (json.charAt(i) != '"') { i++; continue; }
            i++;
            int keyStart = i;
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++;
                i++;
            }
            String key = json.substring(keyStart, i);
            i++;

            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;

            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;

            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            char c = json.charAt(i);
            if (c == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') {
                        i++;
                        if (i < json.length()) sb.append(json.charAt(i));
                    } else {
                        sb.append(json.charAt(i));
                    }
                    i++;
                }
                i++;
                result.put(fullKey, sb.toString());
            } else if (c == '{') {
                int depth = 1;
                int start = i;
                i++;
                while (i < json.length() && depth > 0) {
                    if (json.charAt(i) == '{') depth++;
                    else if (json.charAt(i) == '}') depth--;
                    i++;
                }
                String nested = json.substring(start, i);
                parseJsonKV(nested, fullKey, result);
            } else if (c == '[') {
                // Array value – extract the full array literal and store as string
                int depth = 1;
                int start = i;
                i++;
                while (i < json.length() && depth > 0) {
                    if (json.charAt(i) == '[') depth++;
                    else if (json.charAt(i) == ']') depth--;
                    if (json.charAt(i) == '"') {
                        i++;
                        while (i < json.length() && json.charAt(i) != '"') {
                            if (json.charAt(i) == '\\') i++;
                            i++;
                        }
                    }
                    i++;
                }
                String arrayStr = json.substring(start, i);
                result.put(fullKey, arrayStr);
            } else {
                int start = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                String value = json.substring(start, i).trim();
                result.put(fullKey, value);
            }

            while (i < json.length() && (json.charAt(i) == ',' || Character.isWhitespace(json.charAt(i)))) i++;
        }
    }

    /**
     * Simple YAML key-value parser (flat structure only).
     */
    public static void parseYamlKV(String yaml, Map<String, String> result) {
        String[] lines = yaml.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
    }

    /**
     * Quote an identifier segment for use in SQL queries.
     * Wraps the identifier in backticks and escapes any existing backticks.
     */
    public static String quoteIdentifierSegment(String rawSegment) {
        if (rawSegment == null || rawSegment.isEmpty()) {
            return rawSegment;
        }
        String seg = rawSegment.replace("`", "``");
        return "`" + seg + "`";
    }

    /**
     * Quote a dotted path for use in SQL queries.
     * Splits the path by unescaped dots and quotes each segment individually.
     * Example: data.extern.my-table -> `data`.`extern`.`my-table`
     * Example: data.file\.txt -> `data`.`file\.txt` (backslash-escaped dot is kept in segment)
     */
    public static String quoteIdentifierPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }

        boolean wildcard = rawPath.endsWith(".*");
        String base = wildcard ? rawPath.substring(0, rawPath.length() - 2) : rawPath;

        List<String> segments = splitUnescapedSegmentsForSql(base);
        if (segments.isEmpty()) {
            return wildcard ? "*" : "";
        }

        StringBuilder quoted = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                quoted.append('.');
            }
            quoted.append(quoteIdentifierSegment(segments.get(i)));
        }

        if (wildcard) {
            quoted.append(".*");
        }
        return quoted.toString();
    }

    /**
     * Split a path by unescaped dots for SQL identifier quoting.
     * A dot is considered escaped when it is immediately preceded by a backslash.
     * The backslash is kept in the segment (e.g., "a\.b" remains as one segment "a\.b").
     */
    public static List<String> splitUnescapedSegmentsForSql(String text) {
        List<String> segments = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                current.append('\\');
                escaping = true;
                continue;
            }
            if (c == '.') {
                segments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (escaping) {
            current.append('\\');
        }
        segments.add(current.toString());
        return segments;
    }

}