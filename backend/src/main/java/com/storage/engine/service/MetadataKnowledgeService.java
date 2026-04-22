package com.storage.engine.service;

import com.storage.engine.dao.Neo4jDao;
import com.storage.engine.model.Policy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能元数据服务编排层。
 */
@Service
public class MetadataKnowledgeService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataKnowledgeService.class);
    private static final int LLM_CYPHER_MAX_ATTEMPTS = 2;
    private static final int DEFAULT_CYPHER_QUERY_LIMIT = 80;
    private static final Pattern PATH_PATTERN = Pattern.compile("(/[a-zA-Z0-9_./-]*)");
    private static final Pattern KEYWORD_PATTERN_1 = Pattern.compile("有关(.+?)的");
    private static final Pattern KEYWORD_PATTERN_2 = Pattern.compile("关于(.+?)的");
    private static final Pattern KEYWORD_PATTERN_3 = Pattern.compile("与(.+?)相关");
    private static final Pattern KEYWORD_PATTERN_4 = Pattern.compile("查找(.+?)数据");

    @Autowired
    private Neo4jDao neo4jDao;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private LlmService llmService;

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

        List<String> where = new ArrayList<String>();
        if (!path.isEmpty()) {
            where.add("coalesce(d.logicalPath,'') STARTS WITH '" + escapeLiteral(path) + "'");
        }
        if (!dt.isEmpty()) {
            where.add("toLower(coalesce(d.dataType,'')) = '" + escapeLiteral(dt) + "'");
        }
        if (!kw.isEmpty()) {
            String escapedKeyword = escapeLiteral(kw);
            where.add("("
                + "EXISTS { MATCH (d)-[:MENTIONS]->(eFilter:Entity) "
                + "WHERE toLower(coalesce(eFilter.name,'')) CONTAINS toLower('" + escapedKeyword + "') "
                + "OR toLower(coalesce(eFilter.norm,'')) CONTAINS toLower('" + escapedKeyword + "') } "
                + "OR EXISTS { MATCH (d)-[:HAS_FILED]->(fFilter:Field) "
                + "WHERE toLower(coalesce(fFilter.name,'')) CONTAINS toLower('" + escapedKeyword + "') "
                + "OR toLower(coalesce(fFilter.norm,'')) CONTAINS toLower('" + escapedKeyword + "') }"
                + ")");
        }

        String cypher = "MATCH (d:DataAsset)"
            + appendWhere(where)
            + " WITH DISTINCT d "
            + "OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d) "
            + "OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity) "
            + "OPTIONAL MATCH (d)-[hf:HAS_FILED]->(f:Field) "
            + "OPTIONAL MATCH (e)-[sr:SEMANTIC_RELATION]->(t:Entity) "
            + "RETURN p,hd,d,m,e,hf,f,sr,t";

        String finalCypher = ensureLimit(cypher, DEFAULT_CYPHER_QUERY_LIMIT);
        logger.info("系统参数化查询: logicalPath={}, dataType={}, keyword={}, cypher={}",
                path, dt, kw, finalCypher);
        Map<String, Object> graph = neo4jDao.queryByCypher(finalCypher);
        graph.put("cypher", finalCypher);
        graph.put("strategy", "system");
        graph.put("strategyReason", "structured_filters");
        graph.put("strategyConfidence", 1.0);
        return graph;
    }

    public Map<String, Object> queryByLlmNaturalLanguage(String query) {
        if (!neo4jDao.isEnabled()) {
            return neo4jDao.emptyGraph("Neo4j disabled");
        }

        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return neo4jDao.emptyGraph("Query is empty");
        }

        String schemaHint = "你正在查询一个元数据知识图谱。以下是严格本体模型，请完全遵循。\n"
            + "\n"
            + "[节点标签与属性]\n"
            + "1) LogicalPath\n"
            + "   - path: 逻辑路径主键（示例: /, /project, /project/a）\n"
            + "   - name: 路径段名\n"
            + "   - depth: 层级深度\n"
            + "2) DataAsset\n"
            + "   - logicalPath: 数据资产唯一逻辑路径（示例: /project/a/doc1）\n"
            + "   - dataType: 数据类型（timeseries | relational | keyvalue | document | image）\n"
            + "   - fileName, fileFormat, fileSize, createTime\n"
            + "3) Field\n"
            + "   - ukey: 唯一键\n"
            + "   - norm: 归一化名称\n"
            + "   - kind: 字段类别\n"
            + "   - name: 字段名\n"
            + "4) Entity\n"
            + "   - norm: 归一化实体名（唯一）\n"
            + "   - name: 展示名\n"
            + "\n"
            + "[关系类型与方向]\n"
            + "- (:LogicalPath)-[:CONTAINS]->(:LogicalPath)\n"
            + "- (:LogicalPath)-[:HAS_DATA]->(:DataAsset)\n"
            + "- (:DataAsset)-[:HAS_FILED]->(:Field)\n"
            + "- (:DataAsset)-[:MENTIONS]->(:Entity)\n"
            + "- (:Entity)-[:SEMANTIC_RELATION {relation, sourcePath, updatedAt}]->(:Entity)\n"
            + "\n"
            + "[查询生成原则]\n"
            + "- 必须是只读 Cypher（MATCH/OPTIONAL MATCH/WITH/RETURN/LIMIT）。\n"
            + "- 禁止 CREATE/MERGE/DELETE/SET/REMOVE/CALL/APOC。\n"
            + "- 结果必须返回可绘图子图：优先返回节点和关系对象，不要只返回标量。\n"
            + "- 若问题涉及实体语义，尽量同时返回 DataAsset-[:MENTIONS]->Entity 以及 LogicalPath-[:HAS_DATA]->DataAsset。\n"
            + "- 若用户未明确限制，默认 LIMIT 80。\n"
            + "\n"
            + "[推荐返回模板]\n"
            + "RETURN p,hd,d,m,e,hf,f,sr,t";

        String previousCypher = "";
        String previousError = "";

        for (int attempt = 1; attempt <= LLM_CYPHER_MAX_ATTEMPTS; attempt++) {
            String cypher;
            if (attempt == 1) {
                cypher = llmService.naturalLanguageToCypher(q, schemaHint);
            } else {
                cypher = llmService.naturalLanguageToCypherWithFeedback(q, schemaHint, previousCypher, previousError, attempt);
            }

            logger.info("LLM查询: query={}, attempt={}, llmCypher={}", q, attempt, cypher);
            previousCypher = cypher;

            if (!isSafeReadOnlyCypher(cypher)) {
                previousError = "unsafe_or_empty_cypher";
                logger.warn("LLM Cypher不安全或为空: query={}, attempt={}, cypher={}", q, attempt, cypher);
                continue;
            }

            try {
                String finalCypher = ensureLimit(cypher, DEFAULT_CYPHER_QUERY_LIMIT);
                Map<String, Object> graph = neo4jDao.queryByCypher(finalCypher);
                if (isGraphEmpty(graph)) {
                    previousError = "empty_result";
                    logger.warn("LLM查询结果为空: query={}, attempt={}, cypher={}", q, attempt, finalCypher);
                    continue;
                }

                graph.put("cypher", finalCypher);
                graph.put("strategy", "llm");
                graph.put("strategyReason", attempt == 1 ? "nl_to_cypher" : "nl_to_cypher_retry_success");
                graph.put("strategyConfidence", 0.0);
                graph.put("llmAttempt", attempt);
                if (attempt > 1) {
                    graph.put("llmPreviousError", previousError);
                }
                return graph;
            } catch (Exception e) {
                previousError = safe(e.getMessage());
                logger.warn("LLM Cypher执行失败: query={}, attempt={}, error={}, cypher={}",
                        q, attempt, previousError, cypher);
            }
        }

        logger.warn("LLM Cypher重试耗尽，触发结构化回退: query={}, lastError={}", q, previousError);
        return fallbackByNlQuery(q, "llm_retry_exhausted:" + safe(previousError));
    }

    private String appendWhere(List<String> whereClauses) {
        if (whereClauses == null || whereClauses.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", whereClauses);
    }

    private Map<String, Object> keywordFallback(String keyword) {
        Map<String, Object> graph = neo4jDao.queryByKeyword(keyword);
        graph.put("cypher", "MATCH (n) WHERE ... RETURN n LIMIT 80");
        graph.put("strategy", "keyword_fallback");
        graph.put("strategyReason", "llm_failed_or_unsafe");
        graph.put("strategyConfidence", 0.0);
        graph.put("fallback", true);
        return graph;
    }

    private Map<String, Object> fallbackByNlQuery(String nlQuery, String reason) {
        String dataType = inferDataTypeFromNl(nlQuery);
        String keyword = inferKeywordFromNl(nlQuery);

        if (dataType.isEmpty() && keyword.isEmpty()) {
            Map<String, Object> graph = keywordFallback(nlQuery);
            graph.put("strategyReason", reason + ":keyword_fallback");
            return graph;
        }

        Map<String, Object> graph = queryBySystemFilters("", dataType, keyword);
        graph.put("strategy", "system_fallback");
        graph.put("strategyReason", reason + ":nl_to_system");
        graph.put("strategyConfidence", 0.0);
        graph.put("fallback", true);
        graph.put("fallbackDataType", dataType);
        graph.put("fallbackKeyword", keyword);
        Map<String, Object> fallbackInference = new LinkedHashMap<String, Object>();
        fallbackInference.put("input", safe(nlQuery));
        fallbackInference.put("dataType", dataType);
        fallbackInference.put("keyword", keyword);
        fallbackInference.put("rules", buildFallbackRules(dataType, keyword));
        graph.put("fallbackInference", fallbackInference);
        return graph;
    }

    private List<String> buildFallbackRules(String dataType, String keyword) {
        List<String> rules = new ArrayList<String>();
        rules.add("rule:dataType_from_domain_word");
        rules.add("rule:keyword_from_pattern(有关X的|关于X的|与X相关|查找X数据)");
        if (!dataType.isEmpty()) {
            rules.add("hit:dataType=" + dataType);
        }
        if (!keyword.isEmpty()) {
            rules.add("hit:keyword=" + keyword);
        }
        return rules;
    }

    private String inferDataTypeFromNl(String nlQuery) {
        String q = safe(nlQuery).toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return "";
        }
        if (q.contains("document") || q.contains("文档")) {
            return "document";
        }
        if (q.contains("image") || q.contains("图片") || q.contains("图像")) {
            return "image";
        }
        if (q.contains("timeseries") || q.contains("时序")) {
            return "timeseries";
        }
        if (q.contains("relational") || q.contains("关系")) {
            return "relational";
        }
        if (q.contains("keyvalue") || q.contains("键值")) {
            return "keyvalue";
        }
        return "";
    }

    private String inferKeywordFromNl(String nlQuery) {
        String q = safe(nlQuery);
        if (q.isEmpty()) {
            return "";
        }

        String[] candidates = new String[]{
                firstRegexGroup(KEYWORD_PATTERN_1, q),
                firstRegexGroup(KEYWORD_PATTERN_2, q),
                firstRegexGroup(KEYWORD_PATTERN_3, q),
                firstRegexGroup(KEYWORD_PATTERN_4, q)
        };

        for (String candidate : candidates) {
            String keyword = sanitizeKeyword(candidate);
            if (!keyword.isEmpty()) {
                return keyword;
            }
        }

        return "";
    }

    private String firstRegexGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find() && matcher.groupCount() >= 1) {
            return safe(matcher.group(1));
        }
        return "";
    }

    private String sanitizeKeyword(String value) {
        String keyword = safe(value);
        if (keyword.isEmpty()) {
            return "";
        }
        keyword = keyword.replace("文档", "")
                .replace("数据", "")
                .replace("类型", "")
                .replace("相关", "")
                .trim();
        if (keyword.length() > 32) {
            keyword = keyword.substring(0, 32).trim();
        }
        return keyword;
    }

    private boolean isGraphEmpty(Map<String, Object> graph) {
        if (graph == null) {
            return true;
        }
        Object nodeCount = graph.get("nodeCount");
        if (nodeCount instanceof Number) {
            return ((Number) nodeCount).intValue() <= 0;
        }
        return false;
    }

    private boolean isSafeReadOnlyCypher(String cypher) {
        if (cypher == null || cypher.trim().isEmpty()) {
            return false;
        }
        String c = cypher.trim().toUpperCase(Locale.ROOT);
        String[] banned = new String[]{
                " CREATE ", " MERGE ", " DELETE ", " SET ", " REMOVE ",
                " DROP ", " LOAD CSV ", " FOREACH ", " CALL DBMS", " APOC."
        };

        String wrapped = " " + c + " ";
        for (String b : banned) {
            if (wrapped.contains(b)) {
                return false;
            }
        }

        return c.startsWith("MATCH")
                || c.startsWith("OPTIONAL MATCH")
                || c.startsWith("WITH")
                || c.startsWith("UNWIND");
    }

    private String ensureLimit(String cypher, int defaultLimit) {
        if (cypher == null) return "";
        if (!cypher.matches("(?is).*\\bLIMIT\\b.*")) {
            return cypher.trim() + " LIMIT " + defaultLimit;
        }

        return cypher.trim();
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

    private String escapeLiteral(String value) {
        return safe(value).replace("\\", "\\\\").replace("'", "\\'");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
