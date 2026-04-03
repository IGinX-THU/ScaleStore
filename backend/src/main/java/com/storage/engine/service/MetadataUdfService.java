package com.storage.engine.service;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.dao.IGinxDao;
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

    @Autowired
    private IGinxDao iginxDao;

    public MetadataExtractResult extractByUdf(DataItem item) {
        if (item == null || isBlank(item.getLogicalPath())) {
            throw new IllegalArgumentException("缺少逻辑路径，无法执行元数据UDF抽取");
        }

        String dataPath = StorageUtils.toIginxDataPath(item.getLogicalPath());
        String sql = buildUdfSql(dataPath, item);

        logger.info("执行元数据UDF抽取: logicalPath={}, dataType={}, udf={}",
                item.getLogicalPath(), item.getDataType(), udfName);

        SessionExecuteSqlResult result = executeUdfSqlIsolated(sql);
        return parseUdfResult(result);
    }

    private SessionExecuteSqlResult executeUdfSqlIsolated(String sql) {
        Session session = null;
        try {
            session = new Session(iginxHost, iginxPort, iginxUsername, iginxPassword);
            session.openSession();
            return session.executeSql(sql);
        } catch (SessionException e) {
            logger.warn("独立UDF会话执行失败，将回退连接池会话执行: {}", e.getMessage());
            return iginxDao.executeSql(sql);
        } finally {
            if (session != null) {
                try {
                    session.closeSession();
                } catch (Exception ignore) {
                    // ignore close error
                }
            }
        }
    }

    private String buildUdfSql(String dataPath, DataItem item) {
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
        return "select " + safeUdf + "(*" + args + ") from (select * from " + dataPath + ");";
    }

    private MetadataExtractResult parseUdfResult(SessionExecuteSqlResult result) {
        if (result == null || result.getValues() == null || result.getValues().isEmpty()) {
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
            // ignore and fallback
        }

        String[] parts = trimmed.split(",");
        for (String part : parts) {
            String v = safe(part);
            if (!v.isEmpty()) {
                out.add(v);
            }
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
            logger.warn("解析UDF triples字段失败，将按空结果处理: {}", e.getMessage());
            return triples;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
