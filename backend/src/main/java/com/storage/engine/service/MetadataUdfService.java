package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.exception.SessionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataExtractResult;
import com.storage.engine.service.adapter.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MetadataUdfService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataUdfService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${metadata.extraction.udf-name:metadata_extract}")
    private String udfName;

    @Value("${metadata.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${metadata.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${metadata.llm.api-key:}")
    private String llmApiKey;

    @Value("${metadata.llm.model:}")
    private String llmModel;

    @Value("${metadata.llm.vision-model:}")
    private String llmVisionModel;

    @Value("${metadata.neo4j.enabled:true}")
    private boolean neo4jEnabled;

    @Value("${metadata.neo4j.uri:}")
    private String neo4jUri;

    @Value("${metadata.neo4j.username:}")
    private String neo4jUsername;

    @Value("${metadata.neo4j.password:}")
    private String neo4jPassword;

    @Value("${iginx.host}")
    private String iginxHost;

    @Value("${iginx.port}")
    private int iginxPort;

    @Value("${iginx.username}")
    private String iginxUsername;

    @Value("${iginx.password}")
    private String iginxPassword;

    public MetadataExtractResult extractByUdf(DataItem item) {
        if (item == null || isBlank(item.getLogicalPath())) {
            throw new IllegalArgumentException("缺少逻辑路径，无法执行元数据UDF抽取");
        }

        String dataType = safe(item.getDataType()).toLowerCase(Locale.ROOT);

        String sql = buildUdfSql(item);

        logger.info("执行元数据UDF抽取: logicalPath={}, dataType={}, udf={}",
                item.getLogicalPath(), item.getDataType(), udfName);
        logger.info("[MetadataUDF] SQL: {}", sql);

        SessionExecuteSqlResult result = executeUdfSqlIsolated(sql);
        MetadataExtractResult parsed = parseUdfResult(result);
        MetadataExtractResult normalized = normalizeByDataType(dataType, parsed);
        validateExtractResult(dataType, item, normalized);
        return normalized;
    }

    private SessionExecuteSqlResult executeUdfSqlIsolated(String sql) {
        Session session = null;
        try {
            session = new Session(iginxHost, iginxPort, iginxUsername, iginxPassword);
            session.openSession();
            return session.executeSql(sql);
        } catch (SessionException e) {
            throw new RuntimeException("独立UDF会话执行失败: " + safe(e.getMessage()), e);
        } finally {
            if (session != null) {
                try {
                    session.closeSession();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    private String buildUdfSql(DataItem item) {
        Map<String, String> kvargs = new LinkedHashMap<String, String>();
        kvargs.put("logicalPath", safe(item.getLogicalPath()));
        kvargs.put("dataType", safe(item.getDataType()));
        kvargs.put("fileName", safe(item.getFileName()));
        kvargs.put("fileSize", item.getFileSize() == null ? "" : String.valueOf(item.getFileSize()));
        kvargs.put("fileFormat", safe(item.getFileFormat()));
        kvargs.put("createTime", safe(item.getCreateTime()));
        kvargs.put("llmEnabled", String.valueOf(llmEnabled));
        kvargs.put("llmBaseUrl", safe(llmBaseUrl));
        kvargs.put("llmApiKey", safe(llmApiKey));
        kvargs.put("llmModel", safe(llmModel));
        kvargs.put("llmVisionModel", safe(llmVisionModel));
        kvargs.put("neo4jEnabled", String.valueOf(neo4jEnabled));
        kvargs.put("neo4jUri", safe(neo4jUri));
        kvargs.put("neo4jUsername", safe(neo4jUsername));
        kvargs.put("neo4jPassword", safe(neo4jPassword));

        StringBuilder kvBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : kvargs.entrySet()) {
            String value = entry.getValue();
            if (isBlank(value)) {
                continue;
            }
            if (kvBuilder.length() > 0) {
                kvBuilder.append(", ");
            }
            kvBuilder.append(entry.getKey())
                    .append("='")
                    .append(escapeSql(value))
                    .append("'");
        }

        String safeUdf = safe(udfName);
        if (safeUdf.isEmpty()) {
            safeUdf = "metadata_extract";
        }

        String args = kvBuilder.length() > 0 ? (", " + kvBuilder.toString()) : "";
        String dataType = safe(item.getDataType()).toLowerCase(Locale.ROOT);

        String[] pathPair = resolveParentAndLeaf(item);
        String parentPath = pathPair[0];
        String leafPath = pathPair[1];
        if (isBlank(parentPath) || isBlank(leafPath)) {
            throw new IllegalArgumentException("缺少文件级数据路径，无法构建UDF SQL");
        }

        String sourceSql;
        if (isStructuredType(dataType)) {
            sourceSql = "select * from " + parentPath + "." + leafPath + " limit 0";
        } else if (isSemanticType(dataType)) {
            sourceSql = "select " + leafPath + " from " + parentPath;
        } else {
            sourceSql = "select * from " + parentPath + "." + leafPath;
        }

        return "select " + safeUdf + "(*" + args + ") from (" + sourceSql + ");";
    }

    private String[] resolveParentAndLeaf(DataItem item) {
        if (item == null) {
            return new String[]{"", ""};
        }

        String fullPath = resolveIginxDataPath(item);
        if (isBlank(fullPath)) {
            return new String[]{"", ""};
        }
        return StorageUtils.splitParentAndLeaf(fullPath);
    }

    private MetadataExtractResult parseUdfResult(SessionExecuteSqlResult result) {
        if (result == null || result.getValues() == null || result.getValues().isEmpty()) {
            if (result != null) {
                String printable;
                try {
                    printable = safe(result.getResultInString(true, ",")).replace('\n', ' ');
                } catch (Exception ignore) {
                    printable = "";
                }
                logger.warn("[MetadataUDF] 空结果详情: sqlType={}, parseError={}, paths={}, valuesSize={}, printable={}",
                        result.getSqlType(),
                        safe(result.getParseErrorMsg()),
                        result.getPaths(),
                        result.getValues() == null ? -1 : result.getValues().size(),
                        printable);
            }
            throw new RuntimeException("UDF未返回可解析的数据");
        }

        List<String> paths = result.getPaths() == null ? new ArrayList<String>() : result.getPaths();
        List<Object> row = result.getValues().get(0);

        String status = "";
        String message = "";
        String entitiesRaw = "";
        String fieldsRaw = "";
        String fieldKindRaw = "";
        String triplesRaw = "";

        for (int i = 0; i < paths.size() && i < row.size(); i++) {
            String key = normalizeColumnKey(paths.get(i));
            String value = valueAsString(row.get(i));
            if ("status".equals(key)) {
                status = value;
            } else if ("message".equals(key) || "error".equals(key)) {
                message = value;
            } else if ("entities".equals(key)) {
                entitiesRaw = value;
            } else if ("fields".equals(key)) {
                fieldsRaw = value;
            } else if ("fieldkind".equals(key)) {
                fieldKindRaw = value;
            } else if ("triples".equals(key)) {
                triplesRaw = value;
            }
        }

        if (!isBlank(status) && !"SUCCESS".equalsIgnoreCase(status)) {
            String err = isBlank(message) ? ("UDF执行失败，状态=" + status) : message;
            throw new RuntimeException(err);
        }

        MetadataExtractResult parsed = new MetadataExtractResult();
        parsed.setEntities(parseStringList(entitiesRaw));
        parsed.setFields(parseStringList(fieldsRaw));
        if (!isBlank(fieldKindRaw)) {
            parsed.setFieldKind(fieldKindRaw.trim());
        }
        parsed.setTriples(parseTriples(triplesRaw));
        parsed.setLlmResponse(message);
        parsed.setLlmUsed(false);
        parsed.setLlmError("");

        return parsed;
    }

    private boolean isStructuredType(String dataType) {
        return IGinxConstants.TYPE_RELATIONAL.equals(dataType)
                || IGinxConstants.TYPE_TIMESERIES.equals(dataType)
                || IGinxConstants.TYPE_KEYVALUE.equals(dataType);
    }

    private boolean isSemanticType(String dataType) {
        return IGinxConstants.TYPE_DOCUMENT.equals(dataType)
                || IGinxConstants.TYPE_IMAGE.equals(dataType);
    }

    private void validateExtractResult(String dataType, DataItem item, MetadataExtractResult result) {
        if (result == null) {
            throw new RuntimeException("UDF抽取结果为空");
        }

        if (isStructuredType(dataType)) {
            if (result.getFields() == null || result.getFields().isEmpty()) {
                throw new RuntimeException("结构化抽取结果为空: logicalPath="
                        + safe(item.getLogicalPath()) + ", fileName=" + safe(item.getFileName()));
            }
            return;
        }

        if (isSemanticType(dataType)) {
            boolean emptyEntities = result.getEntities() == null || result.getEntities().isEmpty();
            boolean emptyTriples = result.getTriples() == null || result.getTriples().isEmpty();
            if (emptyEntities && emptyTriples) {
                throw new RuntimeException("语义抽取结果为空: logicalPath="
                        + safe(item.getLogicalPath()) + ", fileName=" + safe(item.getFileName()));
            }
        }
    }

    private MetadataExtractResult normalizeByDataType(String dataType, MetadataExtractResult source) {
        MetadataExtractResult result = source == null ? new MetadataExtractResult() : source;

        if (isStructuredType(dataType)) {
            result.setEntities(new ArrayList<String>());
            result.setTriples(new ArrayList<MetadataExtractResult.SemanticTriple>());
            if (IGinxConstants.TYPE_KEYVALUE.equals(dataType)) {
                if (isBlank(result.getFieldKind())) {
                    result.setFieldKind("key");
                }
            } else {
                if (isBlank(result.getFieldKind()) || "field".equalsIgnoreCase(result.getFieldKind())) {
                    result.setFieldKind("column");
                }
            }
        } else if (IGinxConstants.TYPE_DOCUMENT.equals(dataType) || IGinxConstants.TYPE_IMAGE.equals(dataType)) {
            result.setFields(new ArrayList<String>());
            if (isBlank(result.getFieldKind())) {
                result.setFieldKind("field");
            }
        }

        return result;
    }

    private String normalizeColumnKey(String path) {
        String p = safe(path).toLowerCase(Locale.ROOT);
        if (p.contains("triples")) return "triples";
        if (p.contains("entities")) return "entities";
        if (p.contains("fields")) return "fields";
        if (p.contains("fieldkind") || p.contains("field_kind") || p.contains("field-kind")) return "fieldkind";
        if (p.contains("status")) return "status";
        if (p.contains("message")) return "message";
        if (p.contains("error")) return "error";
        return "";
    }

    private List<String> parseStringList(String raw) {
        List<String> out = new ArrayList<String>();
        if (isBlank(raw)) {
            return out;
        }

        String trimmed = raw.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText(""));
            }
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String v = safe(item.asText(""));
                    if (!v.isEmpty()) {
                        out.add(v);
                    }
                }
                return out;
            }
        } catch (Exception ignore) {
            throw new RuntimeException("解析UDF列表字段失败: " + safe(ignore.getMessage()));
        }
        return out;
    }

    private List<MetadataExtractResult.SemanticTriple> parseTriples(String raw) {
        List<MetadataExtractResult.SemanticTriple> triples = new ArrayList<MetadataExtractResult.SemanticTriple>();
        if (isBlank(raw)) {
            return triples;
        }

        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText(""));
            }
            if (node.isArray()) {
                for (JsonNode tripleNode : node) {
                    MetadataExtractResult.SemanticTriple triple = toTriple(tripleNode);
                    if (triple != null) {
                        triples.add(triple);
                    }
                }
            }
            return triples;
        } catch (Exception e) {
            throw new RuntimeException("解析UDF triples字段失败: " + safe(e.getMessage()), e);
        }
    }

    private MetadataExtractResult.SemanticTriple toTriple(JsonNode node) {
        if (node == null) {
            return null;
        }

        String subject = "";
        String predicate = "";
        String object = "";

        if (node.isArray() && node.size() >= 3) {
            subject = safe(node.get(0).asText(""));
            predicate = safe(node.get(1).asText(""));
            object = safe(node.get(2).asText(""));
        } else if (node.isObject()) {
            subject = safe(node.path("subject").asText(""));
            predicate = safe(node.path("predicate").asText(""));
            object = safe(node.path("object").asText(""));
            if (predicate.isEmpty()) {
                predicate = safe(node.path("relation").asText(""));
            }
        }

        if (subject.isEmpty() || predicate.isEmpty() || object.isEmpty()) {
            return null;
        }
        return new MetadataExtractResult.SemanticTriple(subject, predicate, object);
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private String escapeSql(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveIginxDataPath(DataItem item) {
        if (item == null) {
            return "";
        }

        String logicalPath = safe(item.getLogicalPath());
        String fileName = safe(item.getFileName());

        if (logicalPath.startsWith("/extern/filesystem/")) {
            String fsPath = buildFilesystemExternalPath(logicalPath);
            if (!fsPath.isEmpty()) {
                if (!fileName.isEmpty()) {
                    return StorageUtils.toFileLeafPath(fsPath, fileName);
                }
                return fsPath;
            }
        }

        if (logicalPath.startsWith("/extern/")) {
            String sourceKey = extractExternalSourceKey(logicalPath);
            String externalPath = buildGenericExternalPath(logicalPath);
            if (!externalPath.isEmpty()) {
                if (!fileName.isEmpty()) {
                    if (isFilesystemLikeSourceKey(sourceKey)) {
                        return StorageUtils.toFileLeafPath(externalPath, fileName);
                    }
                    if (isStructuredExternalSourceKey(sourceKey)) {
                        return appendExternalLeafPath(externalPath, fileName);
                    }
                }
                return externalPath;
            }
        }

        if (!logicalPath.isEmpty()) {
            String basePath = StorageUtils.toIginxDataPath(logicalPath);
            if (!fileName.isEmpty()) {
                return StorageUtils.toFileLeafPath(basePath, fileName);
            }
            return basePath;
        }

        return "";
    }

    private String buildGenericExternalPath(String logicalPath) {
        String suffix = logicalPath.substring("/extern/".length());
        if (suffix.isEmpty()) {
            return "data.extern";
        }

        int slash = suffix.indexOf('/');
        String sourceKey = slash >= 0 ? suffix.substring(0, slash) : suffix;
        String externalBody = slash >= 0 ? suffix.substring(slash + 1) : "";

        String schemaPrefix = resolveSchemaPrefixBySourceKey(sourceKey);
        if (isDefaultExternalSourceKey(sourceKey) && externalBody.startsWith(sourceKey + "/")) {
            externalBody = externalBody.substring(sourceKey.length() + 1);
        }

        if (externalBody.isEmpty()) {
            return schemaPrefix;
        }

        String[] segs = externalBody.split("/");
        StringBuilder sb = new StringBuilder(schemaPrefix);
        for (String seg : segs) {
            String cleaned = sanitizeSegment(seg, false);
            if (!cleaned.isEmpty()) {
                sb.append('.').append(cleaned);
            }
        }
        return sb.toString();
    }

    private String buildFilesystemExternalPath(String logicalPath) {
        String prefix = "/extern/filesystem/";
        if (!logicalPath.startsWith(prefix)) {
            return "";
        }
        String body = logicalPath.substring(prefix.length());
        if (body.isEmpty()) {
            return "data.extern";
        }

        String[] segs = body.split("/");
        StringBuilder sb = new StringBuilder("data.extern");
        for (String seg : segs) {
            String cleaned = sanitizeSegment(seg, true);
            if (!cleaned.isEmpty()) {
                sb.append('.').append(cleaned);
            }
        }
        return sb.toString();
    }

    private String sanitizeSegment(String segment, boolean escapeDot) {
        String cleaned = safe(segment)
                .replace("\\", "")
                .replace("/", "_")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        if (escapeDot) {
            cleaned = cleaned.replace(".", "\\\\.");
        }
        return cleaned;
    }

    private String resolveSchemaPrefixBySourceKey(String sourceKey) {
        String key = sanitizeSegment(sourceKey, false);
        if (key.isEmpty() || isDefaultExternalSourceKey(key)) {
            return "data.extern";
        }
        return "data.extern." + key;
    }

    private String extractExternalSourceKey(String logicalPath) {
        String lp = safe(logicalPath);
        if (!lp.startsWith("/extern/")) {
            return "";
        }
        String suffix = lp.substring("/extern/".length());
        int slash = suffix.indexOf('/');
        return slash >= 0 ? suffix.substring(0, slash) : suffix;
    }

    private boolean isDefaultExternalSourceKey(String sourceKey) {
        String key = safe(sourceKey).toLowerCase(Locale.ROOT);
        return "filesystem".equals(key)
                || "mysql".equals(key)
                || "postgres".equals(key)
                || "iotdb".equals(key);
    }

    private boolean isFilesystemLikeSourceKey(String sourceKey) {
        String key = safe(sourceKey).toLowerCase(Locale.ROOT);
        return "filesystem".equals(key) || key.startsWith("filesystem");
    }

    private boolean isStructuredExternalSourceKey(String sourceKey) {
        String key = safe(sourceKey).toLowerCase(Locale.ROOT);
        return "mysql".equals(key) || key.startsWith("mysql")
                || "postgres".equals(key) || key.startsWith("postgres")
                || "iotdb".equals(key) || key.startsWith("iotdb");
    }

    private String appendExternalLeafPath(String basePath, String fileName) {
        String base = safe(basePath);
        String leaf = sanitizeSegment(fileName, false);
        if (base.isEmpty() || leaf.isEmpty()) {
            return base;
        }

        int idx = StorageUtils.findLastUnescapedDot(base);
        String currentLeaf = idx >= 0 ? base.substring(idx + 1) : base;
        currentLeaf = StorageUtils.normalizeEscapedPath(currentLeaf);
        if (currentLeaf.equals(leaf)) {
            return base;
        }
        return base + "." + leaf;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
