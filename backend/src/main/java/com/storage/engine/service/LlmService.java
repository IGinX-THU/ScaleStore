package com.storage.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.model.MetadataExtractResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);
    private static final int DEFAULT_MAX_TEXT_LENGTH = 8000;
    private static final int STRICT_EXTRACTION_MAX_RETRY = 1;

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

    public ExtractResult extractSemanticTriplesFromTextStrict(String dataType, String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text content is empty, cannot extract metadata semantics");
        }
        ensureLlmConfigured();

        try {
            String normalPrompt = buildTextExtractionPrompt(dataType, text, false);
            String retryPrompt = buildTextExtractionPrompt(dataType, text, true);
            return runStrictExtractionWithRetry(
                    model,
                    buildTextExtractionMessages(normalPrompt),
                    buildTextExtractionMessages(retryPrompt),
                    "text");
        } catch (Exception e) {
            logger.error("Text semantic extraction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Text semantic extraction failed: " + e.getMessage(), e);
        }
    }

    public ExtractResult extractSemanticTriplesFromImageStrict(String mimeType, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image content is empty, cannot extract metadata semantics");
        }
        ensureLlmConfigured();

        try {
            String modelToUse = (visionModel != null && !visionModel.trim().isEmpty()) ? visionModel : model;
            return runStrictExtractionWithRetry(
                    modelToUse,
                    buildImageExtractionMessages(mimeType, imageBytes, false),
                    buildImageExtractionMessages(mimeType, imageBytes, true),
                    "image");
        } catch (Exception e) {
            logger.error("Image semantic extraction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Image semantic extraction failed: " + e.getMessage(), e);
        }
    }

    @Deprecated
    public ExtractResult extractEntitiesFromTextStrict(String dataType, String text) {
        return extractSemanticTriplesFromTextStrict(dataType, text);
    }

    @Deprecated
    public ExtractResult extractEntitiesFromImageStrict(String mimeType, byte[] imageBytes) {
        return extractSemanticTriplesFromImageStrict(mimeType, imageBytes);
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
            messages.add(msg("system", "You infer business or semantic relations between data assets. Return strict JSON only."));
            messages.add(msg("user",
                    "Given two data assets and their semantic keywords, decide whether there is a non-parent-child relation. "
                            + "Return {\"relation\":\"\"} if there is no clear relation. "
                            + "If there is one, return {\"relation\":\"short relation\"}; keep it within 24 characters. "
                            + "Input: " + objectMapper.writeValueAsString(payload)));

            String content = chatCompletion(model, messages);
            String raw = stripCodeFence(extractAfterThinkTag(extractPlainText(content))).trim();
            JsonNode root = objectMapper.readTree(raw);
            String relation = root.path("relation").asText("").trim();
            if (relation.length() > 24) {
                relation = relation.substring(0, 24).trim();
            }
            return relation;
        } catch (Exception e) {
            logger.debug("Asset relation inference skipped: {}", e.getMessage());
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
            logger.info("{} semantic extraction returned no triples, retrying once", sourceLabel);
            parsed = parseStrictExtractResult(chatCompletion(modelName, retryMessages), true);
        }
        return parsed;
    }

    private ExtractResult parseStrictExtractResult(String llmText, boolean llmUsed) throws Exception {
        String raw = extractAfterThinkTag(extractPlainText(llmText)).trim();
        if (raw.isEmpty()) {
            throw new RuntimeException("LLM returned empty content");
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
                String value = node.asText("").trim();
                if (!value.isEmpty()) {
                    entities.add(value);
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

    private String resolveChatCompletionsEndpoint(String configuredBaseUrl) {
        String endpoint = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return endpoint;
        }
        return endpoint + "/chat/completions";
    }

    private String buildTextExtractionPrompt(String dataType, String text, boolean retry) {
        String clipped = text.length() > DEFAULT_MAX_TEXT_LENGTH ? text.substring(0, DEFAULT_MAX_TEXT_LENGTH) : text;
        String instruction = retry
                ? "The previous extraction may have returned no usable triples. Re-check the content and extract core semantic triples when possible. "
                : "Extract semantic triples from the input content. ";
        return instruction
                + "Return strict JSON only in this shape: "
                + "{\"triples\":[{\"subject\":\"entity A\",\"predicate\":\"relation\",\"object\":\"entity B\"}],\"entities\":[\"entity A\",\"entity B\"]}. "
                + "Data type: " + safeJson(dataType) + ". "
                + "Triples should reflect actual semantics and may be empty. Use concise predicates. "
                + "Content:\n" + clipped;
    }

    private List<Map<String, Object>> buildTextExtractionMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(msg("system", "You are an information extraction assistant. Return strict JSON only."));
        messages.add(msg("user", prompt));
        return messages;
    }

    private List<Map<String, Object>> buildImageExtractionMessages(String mimeType, byte[] imageBytes, boolean retry) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mt = (mimeType == null || mimeType.trim().isEmpty()) ? "image/png" : mimeType;

        Map<String, Object> textItem = new LinkedHashMap<String, Object>();
        textItem.put("type", "text");
        textItem.put("text", (retry
                ? "Re-check the image and extract semantic triples when possible. "
                : "Extract semantic triples from the image. ")
                + "Return strict JSON only: "
                + "{\"triples\":[{\"subject\":\"entity A\",\"predicate\":\"relation\",\"object\":\"entity B\"}],\"entities\":[\"entity A\",\"entity B\"]}. "
                + "Triples may be empty if no clear relation exists.");

        Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
        imageUrl.put("url", "data:" + mt + ";base64," + base64);

        Map<String, Object> imageItem = new LinkedHashMap<String, Object>();
        imageItem.put("type", "image_url");
        imageItem.put("image_url", imageUrl);

        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", Arrays.asList(textItem, imageItem));

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(msg("system", "You are an image understanding assistant. Return strict JSON only."));
        messages.add(user);
        return messages;
    }

    private Map<String, Object> msg(String role, Object content) {
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
        for (String value : values) {
            String s = value == null ? "" : value.trim();
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

    private void ensureLlmConfigured() {
        if (!enabled) {
            throw new IllegalStateException("metadata.llm.enabled=false, LLM is disabled");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("metadata.llm.api-key is empty");
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
