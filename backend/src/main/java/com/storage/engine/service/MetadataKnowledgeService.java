package com.storage.engine.service;

import com.storage.engine.dao.Neo4jDao;
import com.storage.engine.model.Policy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MetadataKnowledgeService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataKnowledgeService.class);
    private static final int RELATION_BATCH_SIZE = 12;
    private static final Pattern PATH_PATTERN = Pattern.compile("(/[a-zA-Z0-9_./-]*)");

    @Autowired
    private Neo4jDao neo4jDao;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private MetadataExtractionSchedulerService metadataExtractionSchedulerService;

    public Map<String, Object> getGraph(String logicalPath, int limit) {
        if (!neo4jDao.isEnabled()) {
            return neo4jDao.emptyGraph("Neo4j disabled");
        }
        int effectiveLimit = resolveGraphLimit(limit);
        Map<String, Object> graph = neo4jDao.queryGraph(logicalPath, effectiveLimit);
        graph.put("cypher", "MATCH (n)-[r]->(m) ...");
        return graph;
    }

    private int resolveGraphLimit(int requestedLimit) {
        int policyLimit = 200;
        try {
            Policy policy = policyService.getPolicy();
            if (policy != null && policy.getMetadataGraphMaxTriples() != null) {
                policyLimit = policy.getMetadataGraphMaxTriples();
            }
        } catch (Exception e) {
            logger.warn("Failed to load metadata graph policy limit, fallback to default 200: {}", e.getMessage());
        }

        int normalizedRequestedLimit = requestedLimit > 0 ? requestedLimit : policyLimit;
        return Math.max(20, Math.min(normalizedRequestedLimit, policyLimit));
    }

    public Map<String, Object> queryBySystemFilters(String logicalPath,
                                                    String dataType,
                                                    String keyword) {
        if (!neo4jDao.isEnabled()) {
            return neo4jDao.emptyGraph("Neo4j disabled");
        }

        String path = normalizePathFilter(logicalPath);
        String dt = safe(dataType).toLowerCase(Locale.ROOT);
        String kw = safe(keyword);

        int queryLimit = resolveGraphLimit(0);
        String finalCypher = "MATCH DataAsset/LogicalPath WHERE path/type/keyword filters RETURN matched nodes,ancestor paths LIMIT " + queryLimit;
        logger.info("Structured metadata query: logicalPath={}, dataType={}, keyword={}, cypher={}",
                path, dt, kw, finalCypher);
        Map<String, Object> graph = neo4jDao.queryAssetsByKeyword(path, dt, kw, queryLimit);
        graph.put("cypher", finalCypher);
        graph.put("strategy", "system");
        graph.put("strategyReason", "asset_directory_keyword_filters");
        graph.put("strategyConfidence", 1.0);
        scheduleSpecialRelationBatch(graph, kw);
        return graph;
    }

    private void scheduleSpecialRelationBatch(Map<String, Object> graph, String keyword) {
        final List<String> assetIds = extractAssetNodeIds(graph);
        if (assetIds.size() < 2) {
            return;
        }
        CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                computeSpecialRelations(assetIds, keyword);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAssetNodeIds(Map<String, Object> graph) {
        if (graph == null || !(graph.get("nodes") instanceof List)) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (Object obj : (List<Object>) graph.get("nodes")) {
            if (!(obj instanceof Map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) obj;
            if (!"DataAsset".equals(String.valueOf(node.get("categoryName")))) {
                continue;
            }
            String id = String.valueOf(node.get("id"));
            if (!id.isEmpty() && seen.add(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private void computeSpecialRelations(List<String> assetIds, String keyword) {
        try {
            List<Map<String, Object>> pairs = neo4jDao.findUncomputedAssetRelationPairs(assetIds, RELATION_BATCH_SIZE);
            if (pairs.isEmpty()) {
                return;
            }
            metadataExtractionSchedulerService.publishExternalSourceEvent(
                    "running",
                    "RELATION_BATCH",
                    "Special relation batch started: pairs=" + pairs.size() + ", keyword=" + safe(keyword),
                    "Metadata-Agent");
            int persisted = 0;
            for (Map<String, Object> pair : pairs) {
                long leftId = asLong(pair.get("leftId"));
                long rightId = asLong(pair.get("rightId"));
                List<String> leftKeywords = pair.get("leftKeywords") instanceof List
                        ? (List<String>) pair.get("leftKeywords")
                        : Collections.<String>emptyList();
                List<String> rightKeywords = pair.get("rightKeywords") instanceof List
                        ? (List<String>) pair.get("rightKeywords")
                        : Collections.<String>emptyList();
                String relation = inferRelationByOverlap(leftKeywords, rightKeywords);
                if (relation.isEmpty()) {
                    relation = llmService.inferAssetRelation(
                            String.valueOf(pair.get("leftName")),
                            leftKeywords,
                            String.valueOf(pair.get("rightName")),
                            rightKeywords);
                }
                if (!relation.isEmpty()) {
                    neo4jDao.upsertSemanticRelation(leftId, rightId, relation, "metadata-agent");
                    persisted++;
                }
            }
            metadataExtractionSchedulerService.publishExternalSourceEvent(
                    "success",
                    "RELATION_DONE",
                    "Special relation batch completed: persisted=" + persisted + "/" + pairs.size(),
                    "Metadata-Agent");
        } catch (Exception e) {
            logger.debug("Special relation batch skipped: {}", e.getMessage());
            metadataExtractionSchedulerService.publishExternalSourceEvent(
                    "warn",
                    "RELATION_FAILED",
                    "Special relation batch failed: " + safe(e.getMessage()),
                    "Metadata-Agent");
        }
    }

    private String inferRelationByOverlap(List<String> left, List<String> right) {
        Set<String> a = normalizeKeywordSet(left);
        Set<String> b = normalizeKeywordSet(right);
        a.retainAll(b);
        return a.size() >= 2 ? "keyword_related" : "";
    }

    private Set<String> normalizeKeywordSet(List<String> values) {
        Set<String> out = new HashSet<String>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String text = safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private String normalizePathFilter(String input) {
        String p = safe(input);
        if (p.isEmpty()) {
            return "";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        Matcher matcher = PATH_PATTERN.matcher(p);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return p;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
