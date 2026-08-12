package com.storage.engine.dao;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Repository;

import javax.annotation.PreDestroy;
import java.util.*;

/**
 * Neo4j 数据访问对象：封装元数据图谱相关 Cypher 执行。
 */
@Repository
public class Neo4jDao {

	@org.springframework.beans.factory.annotation.Value("${metadata.neo4j.enabled:true}")
	private boolean neo4jEnabled;

	@org.springframework.beans.factory.annotation.Value("${metadata.neo4j.uri:bolt://127.0.0.1:7687}")
	private String neo4jUri;

	@org.springframework.beans.factory.annotation.Value("${metadata.neo4j.username:neo4j}")
	private String neo4jUsername;

	@org.springframework.beans.factory.annotation.Value("${metadata.neo4j.password:neo4j}")
	private String neo4jPassword;

	private volatile Driver driver;
	private volatile boolean constraintsReady = false;

	public boolean isEnabled() {
		return neo4jEnabled;
	}

	/**
	 * 创建图数据库约束（幂等）。
	 */
	public void ensureConstraints() {
		if (!neo4jEnabled || constraintsReady) {
			return;
		}
		synchronized (this) {
			if (constraintsReady) {
				return;
			}
			Session session = getDriver().session();
			try {
				session.run("CREATE CONSTRAINT logical_path_unique IF NOT EXISTS FOR (p:LogicalPath) REQUIRE p.path IS UNIQUE");
				session.run("CREATE CONSTRAINT data_asset_meta_key_unique IF NOT EXISTS FOR (d:DataAsset) REQUIRE d.metaKey IS UNIQUE");
				session.run("CREATE CONSTRAINT entity_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.norm IS UNIQUE");
				constraintsReady = true;
			} finally {
				session.close();
			}
		}
	}

	/**
	 * 按逻辑路径前缀查询图谱子图。
	 */
	public Map<String, Object> queryGraph(String logicalPath, int limit) {
		if (!neo4jEnabled) {
			return emptyGraph("Neo4j disabled");
		}
		ensureConstraints();

		String path = logicalPath == null ? "" : logicalPath.trim();
		int safeLimit = Math.max(20, Math.min(limit, 500));
		int assetWindow = Math.max(10, Math.min(200, safeLimit));

		String cypher = "MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d:DataAsset) "
				+ "WHERE ($path='' OR p.path STARTS WITH $path) "
				+ "WITH p,hd,d ORDER BY coalesce(d.updatedAt, id(d)) DESC, id(d) DESC LIMIT $assetWindow "
				+ "WITH collect(d) AS assets "
				+ "UNWIND range(0, size(assets) - 1) AS assetRank "
				+ "WITH assets[assetRank] AS d, assetRank "
				+ "OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d) "
				+ "OPTIONAL MATCH pathChain=(root:LogicalPath {path:'/'})-[:CONTAINS*0..32]->(p) "
				+ "OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity) "
				+ "WITH pathChain,p,hd,d,m,e, assetRank, coalesce(m.updatedAt,hd.updatedAt,d.updatedAt,id(d)) AS ord "
				+ "ORDER BY assetRank ASC, ord DESC "
				+ "RETURN pathChain,p,hd,d,m,e LIMIT $limit";

		Session session = getDriver().session();
		try {
			List<Record> records = session.readTransaction(new TransactionWork<List<Record>>() {
				@Override
				public List<Record> execute(Transaction tx) {
					Result result = tx.run(cypher, Values.parameters("path", path, "limit", safeLimit, "assetWindow", assetWindow));
					return result.list();
				}
			});

			if (records.isEmpty()) {
				List<Record> nodeOnly = session.readTransaction(new TransactionWork<List<Record>>() {
					@Override
					public List<Record> execute(Transaction tx) {
						Result result = tx.run("MATCH (n) WHERE ($path='' OR coalesce(n.path,n.logicalPath,'') STARTS WITH $path) RETURN n LIMIT $limit",
								Values.parameters("path", path, "limit", safeLimit));
						return result.list();
					}
				});
				return buildGraph(nodeOnly);
			}
			return buildGraph(records);
		} finally {
			session.close();
		}
	}

	/**
	 * 执行只读 Cypher 并返回可视化图结构。
	 */
	/**
	 * 关键词回退查询：按 name/path/logicalPath 模糊匹配节点。
	 */
	public Map<String, Object> emptyGraph(String message) {
		Map<String, Object> graph = new LinkedHashMap<String, Object>();
		graph.put("nodes", Collections.emptyList());
		graph.put("links", Collections.emptyList());
		graph.put("categories", Collections.emptyList());
		graph.put("nodeCount", 0);
		graph.put("linkCount", 0);
		graph.put("message", message);
		return graph;
	}

	private Driver getDriver() {
		if (driver == null) {
			synchronized (this) {
				if (driver == null) {
					driver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword));
				}
			}
		}
		return driver;
	}

	public Map<String, Object> queryAssetsByKeyword(String logicalPath, String dataType, String keyword, int limit) {
		if (!neo4jEnabled) {
			return emptyGraph("Neo4j disabled");
		}
		ensureConstraints();
		int safeLimit = Math.max(20, Math.min(limit, 500));
		int assetWindow = Math.max(10, Math.min(120, safeLimit));
		int directoryWindow = Math.max(10, Math.min(120, safeLimit));
		String path = logicalPath == null ? "" : logicalPath.trim();
		String dt = dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT);
		String kw = keyword == null ? "" : keyword.trim();

		String matchedAssetCypher = "MATCH (d:DataAsset) "
				+ "OPTIONAL MATCH (pd:LogicalPath)-[:HAS_DATA]->(d) "
				+ "WITH d, pd, CASE WHEN pd.path IS NULL THEN '' WHEN pd.path = '/' THEN '/' + d.name ELSE pd.path + '/' + d.name END AS assetPath "
				+ "WHERE ($path='' OR assetPath STARTS WITH $path OR coalesce(pd.path,'') STARTS WITH $path) "
				+ "AND ($dt='' OR toLower(coalesce(d.dataType,'')) = $dt) "
				+ "AND ($kw='' "
				+ "OR toLower(coalesce(d.name,'')) CONTAINS toLower($kw) "
				+ "OR toLower(assetPath) CONTAINS toLower($kw) "
				+ "OR any(k IN coalesce(d.semanticKeywords, []) WHERE toLower(toString(k)) CONTAINS toLower($kw)) "
				+ "OR EXISTS { MATCH (d)-[:MENTIONS]->(e:Entity) WHERE toLower(coalesce(e.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(e.norm,'')) CONTAINS toLower($kw) }) "
				+ "WITH d ORDER BY coalesce(d.updatedAt, id(d)) DESC LIMIT $assetWindow "
				+ "RETURN id(d) AS id";

		String matchedDirectoryCypher = "MATCH (p:LogicalPath) "
				+ "WHERE $dt='' "
				+ "AND ($path='' OR coalesce(p.path,'') STARTS WITH $path) "
				+ "AND ($kw='' "
				+ "OR toLower(coalesce(p.name,'')) CONTAINS toLower($kw) "
				+ "OR toLower(coalesce(p.path,'')) CONTAINS toLower($kw) "
				+ "OR EXISTS { MATCH (p)-[:MENTIONS]->(e:Entity) WHERE toLower(coalesce(e.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(e.norm,'')) CONTAINS toLower($kw) }) "
				+ "WITH p ORDER BY coalesce(p.updatedAt, id(p)) DESC LIMIT $directoryWindow "
				+ "RETURN id(p) AS id";

		String assetGraphCypher = "MATCH (d:DataAsset) WHERE id(d) IN $assetIds "
				+ "OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d) "
				+ "OPTIONAL MATCH pathChain=(root:LogicalPath {path:'/'})-[:CONTAINS*0..32]->(p) "
				+ "OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity) "
				+ "WHERE $kw='' OR toLower(coalesce(e.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(e.norm,'')) CONTAINS toLower($kw) "
				+ "OPTIONAL MATCH (e)-[er:SEMANTIC_RELATION]-(relatedEntity:Entity) "
				+ "WHERE $kw<>'' "
				+ "AND er.assetKey = 'DataAsset::' + toString(d.metaKey) "
				+ "AND coalesce(er.related, true) = true "
				+ "AND EXISTS { MATCH (d)-[:MENTIONS]->(relatedEntity) } "
				+ "OPTIONAL MATCH (d)-[relatedMention:MENTIONS]->(relatedEntity) "
				+ "RETURN pathChain,p,hd,d,m,e,er,relatedEntity,relatedMention LIMIT $limit";

		String directoryGraphCypher = "MATCH (p:LogicalPath) WHERE id(p) IN $directoryIds "
				+ "OPTIONAL MATCH pathChain=(root:LogicalPath {path:'/'})-[:CONTAINS*0..32]->(p) "
				+ "OPTIONAL MATCH (p)-[m:MENTIONS]->(e:Entity) "
				+ "RETURN pathChain,p,m,e LIMIT $limit";

		Session session = getDriver().session();
		try {
			List<Long> assetIds = queryIdList(session, matchedAssetCypher, Values.parameters(
					"path", path,
					"dt", dt,
					"kw", kw,
					"limit", safeLimit,
					"assetWindow", assetWindow));
			List<Long> directoryIds = queryIdList(session, matchedDirectoryCypher, Values.parameters(
					"path", path,
					"dt", dt,
					"kw", kw,
					"limit", safeLimit,
					"directoryWindow", directoryWindow));
			Set<Long> matchedIdSet = new LinkedHashSet<Long>();
			matchedIdSet.addAll(assetIds);
			matchedIdSet.addAll(directoryIds);

			List<Record> records = new ArrayList<Record>();
			if (!assetIds.isEmpty()) {
				records.addAll(session.run(assetGraphCypher, Values.parameters(
						"assetIds", assetIds,
						"limit", safeLimit,
						"kw", kw)).list());
			}
			if (!directoryIds.isEmpty()) {
				records.addAll(session.run(directoryGraphCypher, Values.parameters(
						"directoryIds", directoryIds,
						"limit", safeLimit)).list());
			}
			Map<String, Object> graph = buildGraph(records, matchedIdSet);
			graph.put("matchedNodeIds", new ArrayList<Long>(matchedIdSet));
			return graph;
		} finally {
			session.close();
		}
	}

	public List<String> findMatchedSemanticEntities(List<Long> metaKeys, String keyword) {
		List<String> out = new ArrayList<String>();
		if (!neo4jEnabled || metaKeys == null || metaKeys.isEmpty() || keyword == null || keyword.trim().isEmpty()) {
			return out;
		}
		ensureConstraints();
		List<String> keyTexts = new ArrayList<String>();
		for (Long metaKey : metaKeys) {
			if (metaKey != null && metaKey.longValue() > 0L) {
				keyTexts.add(String.valueOf(metaKey.longValue()));
			}
		}
		if (keyTexts.isEmpty()) {
			return out;
		}

		String cypher = "MATCH (d:DataAsset)-[:MENTIONS]->(e:Entity) "
				+ "WHERE toString(d.metaKey) IN $metaKeys "
				+ "AND (toLower(coalesce(e.name,'')) CONTAINS toLower($keyword) "
				+ "OR toLower(coalesce(e.norm,'')) CONTAINS toLower($keyword)) "
				+ "RETURN DISTINCT e.name AS name ORDER BY name LIMIT 16";
		Session session = getDriver().session();
		try {
			for (Record record : session.run(cypher, Values.parameters(
					"metaKeys", keyTexts,
					"keyword", keyword.trim())).list()) {
				String name = record.get("name").asString("").trim();
				if (!name.isEmpty()) {
					out.add(name);
				}
			}
			return out;
		} finally {
			session.close();
		}
	}

	private List<Long> queryIdList(Session session, String cypher, org.neo4j.driver.Value parameters) {
		List<Long> ids = new ArrayList<Long>();
		List<Record> records = session.run(cypher, parameters).list();
		for (Record record : records) {
			org.neo4j.driver.Value value = record.get("id");
			if (value != null && !value.isNull()) {
				ids.add(value.asLong());
			}
		}
		return ids;
	}

	private Map<String, Object> buildGraph(List<Record> records) {
		return buildGraph(records, Collections.<Long>emptySet());
	}

	private Map<String, Object> buildGraph(List<Record> records, Set<Long> matchedNodeIds) {
		Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<String, Map<String, Object>>();
		Set<String> linkKeys = new LinkedHashSet<String>();
		List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
		Map<String, Integer> categoryMap = new LinkedHashMap<String, Integer>();
		Set<Long> matchedIds = matchedNodeIds == null ? Collections.<Long>emptySet() : matchedNodeIds;

		for (Record record : records) {
			for (String key : record.keys()) {
				org.neo4j.driver.Value value = record.get(key);
				if (value == null || value.isNull()) {
					continue;
				}
				String typeName = value.type().name();
				if ("NODE".equals(typeName)) {
					addNode(value.asNode(), nodeMap, categoryMap);
				} else if ("RELATIONSHIP".equals(typeName)) {
					addRelationship(value.asRelationship(), nodeMap, links, linkKeys);
				} else if ("PATH".equals(typeName)) {
					Path path = value.asPath();
					for (Node n : path.nodes()) {
						addNode(n, nodeMap, categoryMap);
					}
					for (Relationship r : path.relationships()) {
						addRelationship(r, nodeMap, links, linkKeys);
					}
				}
			}
		}

		List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>(nodeMap.values());
		for (Map<String, Object> node : nodes) {
			try {
				node.put("matched", matchedIds.contains(Long.valueOf(String.valueOf(node.get("id")))));
			} catch (Exception ignore) {
				node.put("matched", false);
			}
		}
		List<Map<String, Object>> categories = new ArrayList<Map<String, Object>>();
		for (Map.Entry<String, Integer> entry : categoryMap.entrySet()) {
			Map<String, Object> c = new LinkedHashMap<String, Object>();
			c.put("name", entry.getKey());
			c.put("index", entry.getValue());
			categories.add(c);
		}

		Map<String, Object> graph = new LinkedHashMap<String, Object>();
		graph.put("nodes", nodes);
		graph.put("links", links);
		graph.put("categories", categories);
		graph.put("nodeCount", nodes.size());
		graph.put("linkCount", links.size());
		return graph;
	}

	private void addNode(Node node, Map<String, Map<String, Object>> nodeMap, Map<String, Integer> categoryMap) {
		String id = String.valueOf(node.id());
		Map<String, Object> existing = nodeMap.get(id);
		if (existing != null) {
			String existingName = String.valueOf(existing.get("name"));
			String existingCategoryName = String.valueOf(existing.get("categoryName"));
			if (!("(unknown)".equals(existingName) || "Node".equals(existingCategoryName))) {
				return;
			}
		}

		String label = "Node";
		Iterator<String> labelIt = node.labels().iterator();
		if (labelIt.hasNext()) {
			label = labelIt.next();
		}

		Integer category = categoryMap.get(label);
		if (category == null) {
			category = categoryMap.size();
			categoryMap.put(label, category);
		}

		String name = getNodeName(node);
		int size = calcSizeByLabel(label);

		Map<String, Object> n = new LinkedHashMap<String, Object>();
		n.put("id", id);
		n.put("name", name);
		n.put("category", category);
		n.put("categoryName", label);
		n.put("symbolSize", size);

		Map<String, Object> props = new LinkedHashMap<String, Object>();
		for (String key : node.keys()) {
			if ("updatedAt".equals(key)) {
				continue;
			}
			org.neo4j.driver.Value v = node.get(key);
			props.put(key, v == null || v.isNull() ? null : v.asObject());
		}
		n.put("properties", props);

		nodeMap.put(id, n);
	}

	private void addRelationship(Relationship rel,
								 Map<String, Map<String, Object>> nodeMap,
								 List<Map<String, Object>> links,
								 Set<String> linkKeys) {
		String src = String.valueOf(rel.startNodeId());
		String tgt = String.valueOf(rel.endNodeId());
		String type = rel.type();
		String semanticRelation = "";
		if (rel.containsKey("relation") && rel.get("relation") != null && !rel.get("relation").isNull()) {
			semanticRelation = String.valueOf(rel.get("relation").asObject());
		}
		String key = src + "->" + tgt + ":" + type + ":" + semanticRelation;
		if (linkKeys.contains(key)) {
			return;
		}

		if (!nodeMap.containsKey(src)) {
			Map<String, Object> p = new LinkedHashMap<String, Object>();
			p.put("id", src);
			p.put("name", "(unknown)");
			p.put("category", 0);
			p.put("categoryName", "Node");
			p.put("symbolSize", 20);
			p.put("properties", Collections.emptyMap());
			nodeMap.put(src, p);
		}
		if (!nodeMap.containsKey(tgt)) {
			Map<String, Object> p = new LinkedHashMap<String, Object>();
			p.put("id", tgt);
			p.put("name", "(unknown)");
			p.put("category", 0);
			p.put("categoryName", "Node");
			p.put("symbolSize", 20);
			p.put("properties", Collections.emptyMap());
			nodeMap.put(tgt, p);
		}

		Map<String, Object> link = new LinkedHashMap<String, Object>();
		link.put("source", src);
		link.put("target", tgt);
		link.put("type", type);
		link.put("label", type);
		if (!semanticRelation.isEmpty()) {
			link.put("relationText", semanticRelation);
			link.put("label", semanticRelation);
		}
		links.add(link);
		linkKeys.add(key);
	}

	private String getNodeName(Node node) {
		if (node.hasLabel("LogicalPath")) {
			String name = valueAsNonEmptyString(node, "name");
			if (!name.isEmpty()) {
				return name;
			}

			String path = valueAsNonEmptyString(node, "path");
			if ("/".equals(path)) {
				return "/";
			}
			if (!path.isEmpty()) {
				return leafFromPath(path);
			}
		}

		if (node.hasLabel("DataAsset")) {
			String name = valueAsNonEmptyString(node, "name");
			if (!name.isEmpty()) {
				return name;
			}
			String fileName = valueAsNonEmptyString(node, "fileName");
			if (!fileName.isEmpty()) {
				return fileName;
			}
			String logicalPath = valueAsNonEmptyString(node, "logicalPath");
			if (!logicalPath.isEmpty()) {
				return logicalPath;
			}
		}

		String[] keys = new String[]{"name", "fileName", "path", "logicalPath", "norm"};
		for (String key : keys) {
			String v = valueAsNonEmptyString(node, key);
			if (!v.isEmpty()) {
				return v;
			}
		}
		return "(unknown)";
	}

	private String valueAsNonEmptyString(Node node, String key) {
		org.neo4j.driver.Value value = node.get(key);
		if (value == null || value.isNull()) {
			return "";
		}
		String text = String.valueOf(value.asObject()).trim();
		return text;
	}

	private String leafFromPath(String path) {
		String p = path == null ? "" : path.trim();
		if (p.isEmpty() || "/".equals(p)) {
			return "/";
		}
		while (p.length() > 1 && p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		int idx = p.lastIndexOf('/');
		if (idx >= 0 && idx < p.length() - 1) {
			return p.substring(idx + 1);
		}
		return p;
	}

	private int calcSizeByLabel(String label) {
		if ("LogicalPath".equals(label)) return 42;
		if ("DataAsset".equals(label)) return 36;
		if ("Entity".equals(label)) return 30;
		return 24;
	}

	@PreDestroy
	public void close() {
		if (driver != null) {
			driver.close();
		}
	}
}
