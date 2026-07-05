package com.storage.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.model.MetadataExtractResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class LlmService {

    private static final int DEFAULT_MAX_TEXT_LENGTH = 8000;
    private static final int STRICT_EXTRACTION_MAX_RETRY = 1;
    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${metadata.llm.enabled:true}")
    private boolean enabled;

    @Value("${metadata.llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${metadata.llm.api-key:}")
    private String apiKey;

    @Value("${metadata.llm.model:glm-4-flash}")
    private String model;

    @Value("${metadata.llm.vision-model:}")
    private String visionModel;

    /**
     * 文档类语义抽取：必须调用LLM并严格校验输出JSON结构，但不强制三元组数量。
     */
    public ExtractResult extractSemanticTriplesFromTextStrict(String dataType, String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容为空，无法进行 LLM 实体抽取");
        }
        ensureLlmConfigured();

        try {
            String normalPrompt = buildTextExtractionPrompt(dataType, text, false);
            String retryPrompt = buildTextExtractionPrompt(dataType, text, true);
            return runStrictExtractionWithRetry(
                    model,
                    buildTextExtractionMessages(normalPrompt),
                    buildTextExtractionMessages(retryPrompt),
                    "文档");
        } catch (Exception e) {
            logger.error("文档LLM三元组抽取失败: {}", e.getMessage());
            throw new RuntimeException("文档LLM三元组抽取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 图像类语义抽取：必须调用LLM并严格校验输出JSON结构，但不强制三元组数量。
     */
    public ExtractResult extractSemanticTriplesFromImageStrict(String mimeType, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图像内容为空，无法进行 LLM 实体抽取");
        }
        ensureLlmConfigured();

        try {
            String modelToUse = (visionModel != null && !visionModel.trim().isEmpty()) ? visionModel : model;
            return runStrictExtractionWithRetry(
                    modelToUse,
                    buildImageExtractionMessages(mimeType, imageBytes, false),
                    buildImageExtractionMessages(mimeType, imageBytes, true),
                    "图像");
        } catch (Exception e) {
            logger.error("图像LLM三元组抽取失败: {}", e.getMessage());
            throw new RuntimeException("图像LLM三元组抽取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 兼容旧调用命名。
     */
    @Deprecated
    public ExtractResult extractEntitiesFromTextStrict(String dataType, String text) {
        return extractSemanticTriplesFromTextStrict(dataType, text);
    }

    /**
     * 兼容旧调用命名。
     */
    @Deprecated
    public ExtractResult extractEntitiesFromImageStrict(String mimeType, byte[] imageBytes) {
        return extractSemanticTriplesFromImageStrict(mimeType, imageBytes);
    }

    public String naturalLanguageToCypher(String nlQuery, String schemaHint) {
        if (nlQuery == null || nlQuery.trim().isEmpty()) {
            return "";
        }
        if (!enabled || apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("自然语言转Cypher未执行：LLM未启用或API Key为空");
            return "";
        }
        try {
            String system = "你是Neo4j Cypher专家。"
                    + "你的唯一输出必须是一条可执行的只读Cypher语句。"
                    + "不要返回解释、不要返回Markdown、不要返回注释、不要返回多个语句。";
            String user = "图谱模式提示:\n" + schemaHint + "\n\n用户问题:\n" + nlQuery
                    + "\n\n要求:\n"
                    + "1) 只读查询，不允许写操作。\n"
                    + "2) 优先返回节点与关系本身（如 n,r,m / p,hd,d,m,e），不要只返回计数或字符串。\n"
                    + "3) 实体相关问题必须尽量把来源数据文件带出来：优先包含 DataAsset-[:MENTIONS]->Entity 和 LogicalPath-[:HAS_DATA]->DataAsset。\n"
                    + "4) 仅使用以下关系名: CONTAINS, HAS_DATA, HAS_FIELD, MENTIONS。\n"
                    + "5) 仅使用以下标签名: LogicalPath, DataAsset, Field, Entity。\n"
                    + "6) 不要使用CALL/APOC。\n"
                    + "7) 如果无特殊要求，加 LIMIT 80。";

            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(msg("system", system));
            messages.add(msg("user", user));

            String content = chatCompletion(model, messages);
            String raw = extractAfterThinkTag(extractPlainText(content));
            String cypher = sanitizeCypher(raw);
            cypher = extractReadOnlyCypher(cypher);
            return cypher;
        } catch (Exception e) {
            logger.error("自然语言转Cypher失败: {}", e.getMessage(), e);
            return "";
        }
    }

    public String inferAssetRelation(String leftName,
                                     List<String> leftKeywords,
                                     String rightName,
                                     List<String> rightKeywords) {
        if (!enabled || apiKey == null || apiKey.trim().isEmpty()) {
            return "";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("leftAsset", leftName == null ? "" : leftName);
            payload.put("leftKeywords", leftKeywords == null ? Collections.emptyList() : leftKeywords);
            payload.put("rightAsset", rightName == null ? "" : rightName);
            payload.put("rightKeywords", rightKeywords == null ? Collections.emptyList() : rightKeywords);

            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(msg("system", "你是数据资产关系判断助手。只输出严格JSON。"));
            messages.add(msg("user",
                    "判断两个数据资产是否存在非父子的业务/语义联系。"
                            + "若无明确联系，返回 {\"relation\":\"\"}。"
                            + "若有，返回 {\"relation\":\"简短中文关系\"}，relation 不超过 12 个汉字。"
                            + "输入：" + objectMapper.writeValueAsString(payload)));

            String content = chatCompletion(model, messages);
            String raw = stripCodeFence(extractAfterThinkTag(extractPlainText(content))).trim();
            JsonNode root = objectMapper.readTree(raw);
            String relation = root.path("relation").asText("").trim();
            if (relation.length() > 24) {
                relation = relation.substring(0, 24).trim();
            }
            return relation;
        } catch (Exception e) {
            logger.debug("asset relation inference skipped: {}", e.getMessage());
            return "";
        }
    }

    public String naturalLanguageToCypherWithFeedback(String nlQuery,
                                                      String schemaHint,
                                                      String previousCypher,
                                                      String previousError,
                                                      int attempt) {
        if (nlQuery == null || nlQuery.trim().isEmpty()) {
            return "";
        }
        if (!enabled || apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("Cypher纠错重试未执行：LLM未启用或API Key为空");
            return "";
        }

        try {
            String system = "你是Neo4j Cypher修复专家。"
                    + "你将基于上一条错误Cypher和错误信息，输出一条可执行的只读Cypher。"
                    + "只输出最终Cypher语句，不要解释。";

            String user = "图谱模式提示:\n" + schemaHint
                    + "\n\n用户问题:\n" + nlQuery
                    + "\n\n上一轮失败Cypher:\n" + safeJson(previousCypher)
                    + "\n\n上一轮错误信息:\n" + safeJson(previousError)
                    + "\n\n当前是第" + attempt + "次生成，请严格修复以上错误。"
                    + "\n要求:\n"
                    + "1) 只读查询，不允许写操作。\n"
                    + "2) 严禁引用未定义变量；WITH/RETURN 中变量必须全部已定义。\n"
                    + "3) 能不用 WITH 就不要用 WITH；若使用 WITH，必须显式传递后续会使用的变量。\n"
                    + "4) 仅使用关系: CONTAINS, HAS_DATA, HAS_FIELD, MENTIONS。\n"
                    + "5) 仅使用标签: LogicalPath, DataAsset, Field, Entity。\n"
                    + "6) 优先返回可绘图子图变量（例如 p,hd,d,m,e,hf,f），不要只返回 count。\n"
                    + "7) 无特殊要求时加 LIMIT 80。\n"
                    + "8) 只输出一条Cypher，不要Markdown和解释文本。";

            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            messages.add(msg("system", system));
            messages.add(msg("user", user));

            String content = chatCompletion(model, messages);
            String raw = extractAfterThinkTag(extractPlainText(content));
            String cypher = sanitizeCypher(raw);
            return extractReadOnlyCypher(cypher);
        } catch (Exception e) {
            logger.error("Cypher纠错重试失败: {}", e.getMessage(), e);
            return "";
        }
    }

    private ExtractResult runStrictExtractionWithRetry(String modelName,
                                                       List<Map<String, Object>> primaryMessages,
                                                       List<Map<String, Object>> retryMessages,
                                                       String sourceLabel) throws Exception {
        ExtractResult parsed = parseStrictExtractResult(chatCompletion(modelName, primaryMessages), true);
        for (int i = 0; i < STRICT_EXTRACTION_MAX_RETRY && parsed.getTriples().isEmpty(); i++) {
            if (retryMessages == null || retryMessages.isEmpty()) {
                break;
            }
            logger.info("{}语义抽取首次三元组为空，触发重试", sourceLabel);
            parsed = parseStrictExtractResult(chatCompletion(modelName, retryMessages), true);
        }
        return parsed;
    }

    private ExtractResult parseStrictExtractResult(String llmText, boolean llmUsed) throws Exception {
        String raw = extractAfterThinkTag(extractPlainText(llmText)).trim();
        if (raw.isEmpty()) {
            throw new RuntimeException("LLM返回为空");
        }

        String json = stripCodeFence(raw);
        JsonNode root = objectMapper.readTree(json);
        List<MetadataExtractResult.SemanticTriple> triples = deduplicateTriples(extractTriplesNode(root), 30);

        List<String> entities = extractEntitiesNode(root);
        if (entities.isEmpty()) {
            entities = entitiesFromTriples(triples);
        }
        return new ExtractResult(deduplicate(entities, 20), triples, llmUsed, raw, "");
    }

    private List<String> extractEntitiesNode(JsonNode root) {
        List<String> entities = new ArrayList<String>();
        JsonNode arr = root.path("entities");
        if (!arr.isArray() && root.isArray()) {
            arr = root;
        }
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String v = node.asText("").trim();
                    if (!v.isEmpty()) {
                        entities.add(v);
                    }
                }
            }
        return entities;
    }

    private List<MetadataExtractResult.SemanticTriple> extractTriplesNode(JsonNode root) {
        List<MetadataExtractResult.SemanticTriple> triples = new ArrayList<MetadataExtractResult.SemanticTriple>();
        JsonNode arr = root.path("triples");
        if (!arr.isArray()) {
            arr = root.path("relations");
        }
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String subject = safeJson(node.path("subject").asText(""));
                String predicate = safeJson(node.path("predicate").asText(""));
                String object = safeJson(node.path("object").asText(""));

                if (predicate.isEmpty()) {
                    predicate = safeJson(node.path("relation").asText(""));
                }

                if (subject.isEmpty() || predicate.isEmpty() || object.isEmpty()) {
                    continue;
                }
                triples.add(new MetadataExtractResult.SemanticTriple(subject, predicate, object));
            }
        }
        return triples;
    }

    private String chatCompletion(String modelName, List<Map<String, Object>> messages) throws Exception {
        String endpoint = resolveChatCompletionsEndpoint(baseUrl);

        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("model", modelName);
        req.put("temperature", 0.1);
        req.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        HttpEntity<String> entity = new HttpEntity<String>(objectMapper.writeValueAsString(req), headers);
        ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("LLM request failed: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new RuntimeException("Invalid LLM response");
        }
        return content.isTextual() ? content.asText() : content.toString();
    }

    /**
     * 兼容两种配置方式：
     * 1) base URL，如 http://localhost:8000/v1
     * 2) 完整 endpoint，如 http://localhost:8000/v1/chat/completions
     */
    private String resolveChatCompletionsEndpoint(String configuredBaseUrl) {
        String endpoint = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return endpoint;
        } else {
            return endpoint + "/chat/completions";
        }
    }

    private String buildTextExtractionPrompt(String dataType, String text, boolean retry) {
        String clipped = text.length() > DEFAULT_MAX_TEXT_LENGTH ? text.substring(0, DEFAULT_MAX_TEXT_LENGTH) : text;
        String instruction = retry
                ? "你上一轮可能没有返回可用三元组。请再次检查内容并尽量抽取核心语义关系；若确实无关系，triples返回空数组。"
                : "请从输入内容抽取语义三元组（实体-关系-实体）。";
        return instruction
                + "返回JSON，格式必须是: "
                + "{\"triples\":[{\"subject\":\"实体A\",\"predicate\":\"关系\",\"object\":\"实体B\"}],\"entities\":[\"实体A\",\"实体B\"]}。"
                + "要求:\n"
                + "1) 数据类型: " + dataType + "；\n"
                + "2) triples按实际语义抽取，不强制最小数量，可为空；\n"
                + "3) 每对实体只保留一个最有代表性的关系，不要同时输出语义相同的反向关系；\n"
                + "4) predicate使用简短中文短语；\n"
                + "5) entities可由triples推导并去重。\n"
                + "只输出JSON对象，不要输出任何其他内容。\n内容:\n" + clipped;
    }

    private List<Map<String, Object>> buildTextExtractionMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(msg("system", "你是信息抽取助手，只输出JSON对象。"));
        messages.add(msg("user", prompt));
        return messages;
    }

    private List<Map<String, Object>> buildImageExtractionMessages(String mimeType, byte[] imageBytes, boolean retry) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mt = (mimeType == null || mimeType.trim().isEmpty()) ? "image/png" : mimeType;

        Map<String, Object> textItem = new LinkedHashMap<String, Object>();
        textItem.put("type", "text");
        textItem.put("text", (retry
                ? "请再次检查图像内容，并尽量补充关键语义关系；若确实无关系，triples返回空数组。"
                : "请从图像中抽取语义三元组（实体-关系-实体）。")
            + "只输出JSON: "
            + "{\"triples\":[{\"subject\":\"实体A\",\"predicate\":\"关系\",\"object\":\"实体B\"}],\"entities\":[\"实体A\",\"实体B\"]}，"
            + "按实际内容抽取，可为空；每对实体仅保留一个最有代表性的关系，不要输出语义重复的反向关系。不要输出任何其他内容。");

        Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
        imageUrl.put("url", "data:" + mt + ";base64," + base64);

        Map<String, Object> imageItem = new LinkedHashMap<String, Object>();
        imageItem.put("type", "image_url");
        imageItem.put("image_url", imageUrl);

        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", Arrays.asList(textItem, imageItem));

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(msg("system", "你是图像内容理解助手，只输出JSON对象。"));
        messages.add(user);
        return messages;
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String extractPlainText(String content) {
        if (content == null) {
            return "";
        }
        return new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * 一些本地模型会返回 <think>...</think> + JSON，本方法仅保留最后一个 </think> 之后的内容。
     */
    private String extractAfterThinkTag(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf("</think>");
        if (idx >= 0) {
            int start = idx + "</think>".length();
            if (start < text.length()) {
                return text.substring(start);
            }
            return "";
        }
        return text;
    }

    private String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```") && t.endsWith("```")) {
            int firstNewLine = t.indexOf('\n');
            if (firstNewLine > -1) {
                t = t.substring(firstNewLine + 1, t.length() - 3).trim();
            }
        }
        return t;
    }

    private List<String> deduplicate(List<String> values, int max) {
        Set<String> set = new LinkedHashSet<String>();
        for (String v : values) {
            String s = v == null ? "" : v.trim();
            if (!s.isEmpty()) {
                set.add(s);
            }
            if (set.size() >= max) {
                break;
            }
        }
        return new ArrayList<String>(set);
    }

    private List<MetadataExtractResult.SemanticTriple> deduplicateTriples(List<MetadataExtractResult.SemanticTriple> values, int max) {
        List<MetadataExtractResult.SemanticTriple> list = new ArrayList<MetadataExtractResult.SemanticTriple>();
        Set<String> seen = new LinkedHashSet<String>();
        Set<String> pairSeen = new LinkedHashSet<String>();
        for (MetadataExtractResult.SemanticTriple triple : values) {
            if (triple == null) {
                continue;
            }
            String subject = safeJson(triple.getSubject());
            String predicate = safeJson(triple.getPredicate());
            String object = safeJson(triple.getObject());
            if (subject.isEmpty() || predicate.isEmpty() || object.isEmpty()) {
                continue;
            }
            String key = normalizeDisplay(subject) + "|" + normalizeDisplay(predicate) + "|" + normalizeDisplay(object);
            if (seen.contains(key)) {
                continue;
            }

            // 每对实体只保留一个关系，避免“熊猫吃竹子/竹子被熊猫吃”这种反向重复。
            String normSubject = normalizeForPair(subject);
            String normObject = normalizeForPair(object);
            if (normSubject.isEmpty() || normObject.isEmpty()) {
                continue;
            }
            String pairKey = normSubject.compareTo(normObject) <= 0
                    ? normSubject + "||" + normObject
                    : normObject + "||" + normSubject;
            if (pairSeen.contains(pairKey)) {
                continue;
            }

            seen.add(key);
            pairSeen.add(pairKey);
            list.add(new MetadataExtractResult.SemanticTriple(subject, predicate, object));
            if (list.size() >= max) {
                break;
            }
        }
        return list;
    }

    private String normalizeForPair(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private List<String> entitiesFromTriples(List<MetadataExtractResult.SemanticTriple> triples) {
        List<String> entities = new ArrayList<String>();
        for (MetadataExtractResult.SemanticTriple triple : triples) {
            if (triple == null) {
                continue;
            }
            String subject = safeJson(triple.getSubject());
            String object = safeJson(triple.getObject());
            if (!subject.isEmpty()) {
                entities.add(subject);
            }
            if (!object.isEmpty()) {
                entities.add(object);
            }
        }
        return deduplicate(entities, 20);
    }

    private String safeJson(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeDisplay(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String sanitizeCypher(String raw) {
        if (raw == null) {
            return "";
        }
        String cypher = stripCodeFence(raw).trim();
        if (cypher.endsWith(";")) {
            cypher = cypher.substring(0, cypher.length() - 1);
        }
        return cypher;
    }

    /**
     * 从混合输出中提取首条只读Cypher语句。
     */
    private String extractReadOnlyCypher(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String t = text.trim();

        String upper = t.toUpperCase(Locale.ROOT);
        int start = indexOfFirstCypherClause(upper);
        if (start < 0) {
            return "";
        }

        String candidate = t.substring(start).trim();
        int semicolon = candidate.indexOf(';');
        if (semicolon >= 0) {
            candidate = candidate.substring(0, semicolon).trim();
        }
        return candidate;
    }

    private int indexOfFirstCypherClause(String upperText) {
        int idx = -1;
        String[] clauses = new String[]{"MATCH", "OPTIONAL MATCH", "WITH", "UNWIND"};
        for (String clause : clauses) {
            int i = upperText.indexOf(clause);
            if (i >= 0 && (idx < 0 || i < idx)) {
                idx = i;
            }
        }
        return idx;
    }

    private void ensureLlmConfigured() {
        if (!enabled) {
            throw new IllegalStateException("metadata.llm.enabled=false，LLM未启用");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("metadata.llm.api-key为空，无法调用LLM");
        }
    }

    public static class ExtractResult {
        private final List<String> entities;
        private final List<MetadataExtractResult.SemanticTriple> triples;
        private final boolean llmUsed;
        private final String rawResponse;
        private final String error;

        public ExtractResult(List<String> entities,
                             List<MetadataExtractResult.SemanticTriple> triples,
                             boolean llmUsed,
                             String rawResponse,
                             String error) {
            this.entities = entities == null ? Collections.<String>emptyList() : entities;
            this.triples = triples == null ? Collections.<MetadataExtractResult.SemanticTriple>emptyList() : triples;
            this.llmUsed = llmUsed;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
            this.error = error == null ? "" : error;
        }

        public List<String> getEntities() {
            return entities;
        }

        public List<MetadataExtractResult.SemanticTriple> getTriples() {
            return triples;
        }

        public boolean isLlmUsed() {
            return llmUsed;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public String getError() {
            return error;
        }
    }
}
