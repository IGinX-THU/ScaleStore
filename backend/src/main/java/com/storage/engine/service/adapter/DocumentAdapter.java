package com.storage.engine.service.adapter;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for JSON/XML document data.
 * Documents are flattened into IGinX columns, matching MongoDB-style expanded paths.
 */
@Component
public class DocumentAdapter implements StorageAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DocumentAdapter.class);
    private static final long DOCUMENT_KEY_STEP = 1L << 32;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String format = StorageUtils.getFileExtension(file.getOriginalFilename());

        List<Object> documents = "xml".equalsIgnoreCase(format)
                ? parseXmlDocuments(content)
                : parseJsonDocuments(content);

        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No valid document found in file");
        }

        LinkedHashMap<String, ColumnValues> columns = new LinkedHashMap<String, ColumnValues>();
        for (int i = 0; i < documents.size(); i++) {
            long baseKey = (i + 1L) * DOCUMENT_KEY_STEP;
            flattenValue("", documents.get(i), baseKey, columns);
        }

        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Document contains no scalar fields");
        }

        for (Map.Entry<String, ColumnValues> entry : columns.entrySet()) {
            ColumnValues column = entry.getValue();
            List<String> paths = Collections.singletonList(iginxPath + "." + entry.getKey());
            List<DataType> types = Collections.singletonList(column.type);
            Object[] valuesList = new Object[]{column.toArray()};
            iginxDao.insertColumnRecords(paths, column.keysArray(), valuesList, types);
        }

        long[] keys = collectKeys(columns.values());
        logger.info("Document stored as expanded columns: path={}, docs={}, columns={}, keys={}",
                iginxPath, documents.size(), columns.size(), keys.length);
    }

    @Override
    public Object getPreviewData(String iginxPath, int limit) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPathWithLimit(iginxPath, limit);
        return buildTablePreview(result, iginxPath);
    }

    @Override
    public byte[] getDownloadBytes(String iginxPath) throws Exception {
        SessionExecuteSqlResult result = iginxDao.queryDataByPath(iginxPath);
        Object preview = buildTablePreview(result, iginxPath);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(preview);
    }

    private List<Object> parseJsonDocuments(String content) throws Exception {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(text);
            return jsonRootToDocuments(root);
        } catch (Exception ignored) {
            // Continue with common multi-document formats.
        }

        String normalized = text.replace("\r\n", "\n");
        String[] candidates = normalized.contains(";\n")
                ? normalized.split(";\\s*\\n")
                : normalized.split("\\n(?=\\s*\\{)");

        List<Object> docs = new ArrayList<Object>();
        for (String candidate : candidates) {
            String part = candidate.trim();
            while (part.endsWith(";")) {
                part = part.substring(0, part.length() - 1).trim();
            }
            if (part.isEmpty()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(part);
            docs.addAll(jsonRootToDocuments(node));
        }
        return docs;
    }

    private List<Object> jsonRootToDocuments(JsonNode root) {
        List<Object> docs = new ArrayList<Object>();
        if (root == null || root.isNull()) {
            return docs;
        }
        if (root.isArray()) {
            for (JsonNode child : root) {
                docs.add(jsonToJava(child));
            }
        } else {
            docs.add(jsonToJava(root));
        }
        return docs;
    }

    private Object jsonToJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                map.put(field.getKey(), jsonToJava(field.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<Object>();
            for (JsonNode child : node) {
                list.add(jsonToJava(child));
            }
            return list;
        }
        if (node.isBoolean()) {
            return Boolean.valueOf(node.booleanValue());
        }
        if (node.isInt() || node.isLong()) {
            long value = node.longValue();
            if (value <= Integer.MAX_VALUE && value >= Integer.MIN_VALUE) {
                return Integer.valueOf((int) value);
            }
            return Long.valueOf(value);
        }
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) {
            return Double.valueOf(node.doubleValue());
        }
        return node.asText();
    }

    private List<Object> parseXmlDocuments(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setIgnoringComments(true);
        factory.setCoalescing(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // Some JDK XML parsers do not expose all security features.
        }

        org.w3c.dom.Document doc = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(content)));
        Element root = doc.getDocumentElement();
        Object converted = xmlElementToJava(root);

        List<Object> docs = new ArrayList<Object>();
        if (converted instanceof List) {
            docs.addAll((List<?>) converted);
        } else {
            docs.add(converted);
        }
        return docs;
    }

    private Object xmlElementToJava(Element element) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            map.put("@" + attr.getNodeName(), attr.getNodeValue());
        }

        LinkedHashMap<String, List<Object>> childrenByName = new LinkedHashMap<String, List<Object>>();
        NodeList children = element.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String name = child.getNodeName();
                List<Object> values = childrenByName.get(name);
                if (values == null) {
                    values = new ArrayList<Object>();
                    childrenByName.put(name, values);
                }
                values.add(xmlElementToJava((Element) child));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String value = child.getTextContent();
                if (value != null && !value.trim().isEmpty()) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(value.trim());
                }
            }
        }

        for (Map.Entry<String, List<Object>> entry : childrenByName.entrySet()) {
            List<Object> values = entry.getValue();
            map.put(entry.getKey(), values.size() == 1 ? values.get(0) : values);
        }

        if (map.isEmpty()) {
            return inferScalar(text.toString());
        }
        if (text.length() > 0) {
            map.put("#text", inferScalar(text.toString()));
        }
        return map;
    }

    private void flattenValue(String path,
                              Object value,
                              long key,
                              LinkedHashMap<String, ColumnValues> columns) {
        if (value == null) {
            return;
        }

        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (isObjectIdMap(map)) {
                putColumnValue(path, "ObjectId(\"" + String.valueOf(map.get("$oid")) + "\")", key, columns);
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String child = sanitizePathSegment(String.valueOf(entry.getKey()));
                if (child.isEmpty()) {
                    continue;
                }
                String childPath = path.isEmpty() ? child : path + "." + child;
                flattenValue(childPath, entry.getValue(), key, columns);
            }
            return;
        }

        if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                flattenValue(path, list.get(i), key + i, columns);
            }
            return;
        }

        if (path.isEmpty()) {
            putColumnValue("value", value, key, columns);
        } else {
            putColumnValue(path, value, key, columns);
        }
    }

    private boolean isObjectIdMap(Map<?, ?> map) {
        return map != null && map.size() == 1 && map.containsKey("$oid");
    }

    private void putColumnValue(String path,
                                Object value,
                                long key,
                                LinkedHashMap<String, ColumnValues> columns) {
        if (path == null || path.trim().isEmpty() || value == null) {
            return;
        }
        ColumnValues column = columns.get(path);
        DataType type = inferDataType(value);
        if (column == null) {
            column = new ColumnValues(type);
            columns.put(path, column);
        } else if (column.type != type) {
            column.convertToBinary();
        }
        column.put(key, value);
    }

    private DataType inferDataType(Object value) {
        if (value instanceof Boolean) {
            return DataType.BOOLEAN;
        }
        if (value instanceof Integer) {
            return DataType.INTEGER;
        }
        if (value instanceof Long) {
            return DataType.LONG;
        }
        if (value instanceof Float || value instanceof Double) {
            return DataType.DOUBLE;
        }
        return DataType.BINARY;
    }

    private Object inferScalar(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return "";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    private long[] collectKeys(Collection<ColumnValues> columns) {
        TreeSet<Long> keys = new TreeSet<Long>();
        for (ColumnValues column : columns) {
            keys.addAll(column.values.keySet());
        }
        long[] out = new long[keys.size()];
        int i = 0;
        for (Long key : keys) {
            out[i++] = key.longValue();
        }
        return out;
    }

    private Map<String, Object> buildTablePreview(SessionExecuteSqlResult result, String assetPath) {
        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        List<String> columns = new ArrayList<String>();
        List<List<Object>> rows = new ArrayList<List<Object>>();

        if (result == null) {
            preview.put("columns", columns);
            preview.put("rows", rows);
            preview.put("totalRows", 0);
            return preview;
        }

        List<String> paths = result.getPaths() == null ? Collections.<String>emptyList() : result.getPaths();
        List<List<Object>> values = result.getValues() == null ? Collections.<List<Object>>emptyList() : result.getValues();
        long[] keys = result.getKeys();

        boolean firstPathIsKey = !paths.isEmpty() && "key".equalsIgnoreCase(extractColumnName(paths.get(0)));
        for (int i = firstPathIsKey ? 1 : 0; i < paths.size(); i++) {
            columns.add(extractRelativeColumnName(paths.get(i), assetPath));
        }

        for (int i = 0; i < values.size(); i++) {
            List<Object> valueRow = values.get(i);
            if (valueRow == null) {
                continue;
            }
            List<Object> row = new ArrayList<Object>();
            int valueStart = 0;
            if (keys != null && keys.length == values.size()) {
                if (firstPathIsKey) {
                    valueStart = 1;
                }
            } else if (firstPathIsKey && !valueRow.isEmpty()) {
                valueStart = 1;
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

    private String extractRelativeColumnName(String path, String assetPath) {
        if (path == null) {
            return "";
        }
        String column = StorageUtils.normalizeEscapedPath(path);
        String asset = StorageUtils.normalizeEscapedPath(assetPath);
        if (!asset.isEmpty() && column.startsWith(asset + ".")) {
            return column.substring(asset.length() + 1);
        }
        return column;
    }

    private String extractColumnName(String path) {
        if (path == null) {
            return "";
        }
        int lastDot = path.lastIndexOf('.');
        String token = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        return token.replace("\\\\.", ".").replace("\\.", ".");
    }

    private String sanitizePathSegment(String name) {
        if (name == null) {
            return "";
        }
        String sanitized = name.trim().replaceAll("[^a-zA-Z0-9_@$-]", "_");
        if (sanitized.isEmpty()) {
            return "";
        }
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "c_" + sanitized;
        }
        return sanitized;
    }

    private static class ColumnValues {
        private DataType type;
        private final TreeMap<Long, Object> values = new TreeMap<Long, Object>();

        private ColumnValues(DataType type) {
            this.type = type == null ? DataType.BINARY : type;
        }

        private void put(long key, Object value) {
            if (type == DataType.BINARY && !(value instanceof byte[])) {
                values.put(Long.valueOf(key), String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            } else {
                values.put(Long.valueOf(key), value);
            }
        }

        private void convertToBinary() {
            if (type == DataType.BINARY) {
                return;
            }
            TreeMap<Long, Object> converted = new TreeMap<Long, Object>();
            for (Map.Entry<Long, Object> entry : values.entrySet()) {
                converted.put(entry.getKey(), String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.putAll(converted);
            type = DataType.BINARY;
        }

        private Object toArray() {
            if (type == DataType.BOOLEAN) {
                Boolean[] out = new Boolean[values.size()];
                int i = 0;
                for (Object value : values.values()) {
                    out[i++] = Boolean.valueOf(Boolean.TRUE.equals(value));
                }
                return out;
            }
            if (type == DataType.INTEGER) {
                Integer[] out = new Integer[values.size()];
                int i = 0;
                for (Object value : values.values()) {
                    out[i++] = Integer.valueOf(((Number) value).intValue());
                }
                return out;
            }
            if (type == DataType.LONG) {
                Long[] out = new Long[values.size()];
                int i = 0;
                for (Object value : values.values()) {
                    out[i++] = Long.valueOf(((Number) value).longValue());
                }
                return out;
            }
            if (type == DataType.DOUBLE) {
                Double[] out = new Double[values.size()];
                int i = 0;
                for (Object value : values.values()) {
                    out[i++] = Double.valueOf(((Number) value).doubleValue());
                }
                return out;
            }
            byte[][] out = new byte[values.size()][];
            int i = 0;
            for (Object value : values.values()) {
                out[i++] = value instanceof byte[]
                        ? (byte[]) value
                        : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            }
            return out;
        }

        private long[] keysArray() {
            long[] out = new long[values.size()];
            int i = 0;
            for (Long key : values.keySet()) {
                out[i++] = key.longValue();
            }
            return out;
        }
    }
}
