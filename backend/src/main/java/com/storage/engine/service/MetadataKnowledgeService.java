package com.storage.engine.service;

import com.storage.engine.dao.Neo4jDao;
import com.storage.engine.model.Policy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MetadataKnowledgeService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataKnowledgeService.class);
    private static final Pattern PATH_PATTERN = Pattern.compile("(/[a-zA-Z0-9_./-]*)");

    @Autowired
    private Neo4jDao neo4jDao;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private MetadataExtractionSchedulerService metadataExtractionSchedulerService;

    public Map<String, Object> getGraph(String logicalPath, int maxNodes) {
        if (!neo4jDao.isEnabled()) {
            return neo4jDao.emptyGraph("Neo4j disabled");
        }
        int effectiveMaxNodes = resolveGraphMaxNodes(maxNodes);
        Map<String, Object> graph = neo4jDao.queryGraph(logicalPath, effectiveMaxNodes);
        graph.put("cypher", "MATCH (n)-[r]->(m) ...");
        return graph;
    }

    private int resolveGraphMaxNodes(int requestedMaxNodes) {
        int policyMaxNodes = 200;
        try {
            Policy policy = policyService.getPolicy();
            if (policy != null && policy.getMetadataGraphMaxNodes() != null) {
                policyMaxNodes = policy.getMetadataGraphMaxNodes();
            }
        } catch (Exception e) {
            logger.warn("Failed to load metadata graph policy limit, fallback to default 200: {}", e.getMessage());
        }

        int normalizedRequestedMaxNodes = requestedMaxNodes > 0 ? requestedMaxNodes : policyMaxNodes;
        return Math.max(20, Math.min(normalizedRequestedMaxNodes, policyMaxNodes));
    }

    public Map<String, Object> queryBySystemFilters(String logicalPath,
                                                    String dataType,
                                                    String keyword) {
        return queryBySystemFilters(logicalPath, dataType, keyword, true);
    }

    public Map<String, Object> queryBySystemFilters(String logicalPath,
                                                    String dataType,
                                                    String keyword,
                                                    boolean expandRelations) {
        if (!neo4jDao.isEnabled()) {
            return neo4jDao.emptyGraph("Neo4j disabled");
        }

        String path = normalizePathFilter(logicalPath);
        String dt = safe(dataType).toLowerCase(Locale.ROOT);
        String kw = safe(keyword);

        int queryMaxNodes = resolveGraphMaxNodes(0);
        String finalCypher = "MATCH DataAsset/LogicalPath WHERE path/type/keyword filters RETURN matched nodes,ancestor paths MAX_NODES " + queryMaxNodes;
        logger.info("Structured metadata query: logicalPath={}, dataType={}, keyword={}, cypher={}",
                path, dt, kw, finalCypher);
        Map<String, Object> graph = neo4jDao.queryAssetsByKeyword(path, dt, kw, queryMaxNodes);
        graph.put("cypher", finalCypher);
        graph.put("strategy", "system");
        graph.put("strategyReason", "asset_directory_keyword_filters");
        graph.put("strategyConfidence", 1.0);
        if (expandRelations) {
            scheduleEntityRelationExpansion(graph, kw);
        }
        return graph;
    }

    private void scheduleEntityRelationExpansion(Map<String, Object> graph, String keyword) {
        List<Long> metaKeys = extractMatchedAssetMetaKeys(graph);
        if (metaKeys.isEmpty()) {
            return;
        }
        List<String> matchedEntities = neo4jDao.findMatchedSemanticEntities(metaKeys, safe(keyword));
        if (matchedEntities.isEmpty()) {
            logger.info("Skip entity relation expansion: keyword={}, no matched semantic entity", safe(keyword));
            return;
        }
        for (String focus : matchedEntities) {
            metadataExtractionSchedulerService.expandEntityRelationsAsync(focus, metaKeys);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> extractMatchedAssetMetaKeys(Map<String, Object> graph) {
        List<Long> out = new ArrayList<Long>();
        Set<Long> seen = new HashSet<Long>();
        if (graph == null || !(graph.get("nodes") instanceof List)) {
            return out;
        }
        for (Object rawNode : (List<Object>) graph.get("nodes")) {
            if (!(rawNode instanceof Map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) rawNode;
            if (!"DataAsset".equals(String.valueOf(node.get("categoryName")))
                    || !(node.get("properties") instanceof Map)) {
                continue;
            }
            Map<String, Object> properties = (Map<String, Object>) node.get("properties");
            try {
                long metaKey = Long.parseLong(String.valueOf(properties.get("metaKey")));
                if (metaKey > 0 && seen.add(metaKey)) {
                    out.add(metaKey);
                }
            } catch (Exception ignore) {
            }
        }
        return out;
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
