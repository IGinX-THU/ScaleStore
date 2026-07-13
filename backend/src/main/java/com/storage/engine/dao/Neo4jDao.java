package com.storage.engine.dao;

import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataExtractResult;
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
				session.run("DROP CONSTRAINT data_asset_unique IF EXISTS");
				session.run("CREATE CONSTRAINT data_asset_ukey_unique IF NOT EXISTS FOR (d:DataAsset) REQUIRE d.ukey IS UNIQUE");
				session.run("CREATE CONSTRAINT field_unique IF NOT EXISTS FOR (f:Field) REQUIRE f.ukey IS UNIQUE");
				session.run("CREATE CONSTRAINT entity_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.norm IS UNIQUE");
				session.run("MATCH (d:DataAsset)-[old:HAS_FILED]->(f:Field) "
						+ "MERGE (d)-[r:HAS_FIELD]->(f) "
						+ "SET r.updatedAt=coalesce(old.updatedAt,timestamp()) "
						+ "DELETE old");
				constraintsReady = true;
			} finally {
				session.close();
			}
		}
	}

	/**
	 * 将单条数据资产及其抽取语义写入 Neo4j 图谱。
	 */
	public void upsertKnowledgeGraph(DataItem item, MetadataExtractResult semantics) {
		if (!neo4jEnabled || item == null) {
			return;
		}
		ensureConstraints();

		final String logicalPath = normalizePath(item.getLogicalPath());
		final String dataType = safe(item.getDataType());
		final String fileName = safe(item.getFileName());
		final String fileFormat = safe(item.getFileFormat());
		final long fileSize = item.getFileSize() == null ? 0L : item.getFileSize();
		final String createTime = safe(item.getCreateTime());

		final MetadataExtractResult s = semantics == null ? new MetadataExtractResult() : semantics;
		final List<String> pathChain = buildPathChain(logicalPath);

		Session session = getDriver().session();
		try {
			session.writeTransaction(new TransactionWork<Void>() {
				@Override
				public Void execute(Transaction tx) {
					mergePathHierarchy(tx, pathChain);
					mergeAsset(tx, logicalPath, dataType, fileName, fileFormat, fileSize, createTime);
					linkAssetToPath(tx, logicalPath);
					mergeFields(tx, logicalPath, s.getFields(), s.getFieldKind());
					mergeEntities(tx, logicalPath, s.getEntities());
					mergeSemanticTriples(tx, logicalPath, s.getTriples());
					return null;
				}
			});
		} finally {
			session.close();
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

		String cypher = "MATCH (d:DataAsset) "
				+ "WHERE ($path='' OR coalesce(d.logicalPath,'') STARTS WITH $path) "
				+ "WITH d ORDER BY coalesce(d.updatedAt, id(d)) DESC, id(d) DESC LIMIT $assetWindow "
				+ "WITH collect(d) AS assets "
				+ "UNWIND range(0, size(assets) - 1) AS assetRank "
				+ "WITH assets[assetRank] AS d, assetRank "
				+ "OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d) "
				+ "OPTIONAL MATCH pathChain=(root:LogicalPath {path:'/'})-[:CONTAINS*0..32]->(p) "
				+ "OPTIONAL MATCH (parent:DataAsset)-[ca:CONTAINS_ASSET]->(d) "
				+ "OPTIONAL MATCH (d)-[ca2:CONTAINS_ASSET]->(child:DataAsset) "
				+ "OPTIONAL MATCH (d)-[sr:SEMANTIC_RELATION]-(related:DataAsset) "
				+ "OPTIONAL MATCH (d)-[hf:HAS_FIELD]->(f:Field) "
				+ "OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity) "
				+ "WITH pathChain,p,hd,d,parent,ca,ca2,child,sr,related,hf,f,m,e, assetRank, coalesce(m.updatedAt,hf.updatedAt,hd.updatedAt,d.updatedAt,id(d)) AS ord "
				+ "ORDER BY assetRank ASC, ord DESC "
				+ "RETURN pathChain,p,hd,d,parent,ca,ca2,child,sr,related,hf,f,m,e LIMIT $limit";

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
	public Map<String, Object> queryByCypher(String cypher) {
		if (!neo4jEnabled) {
			return emptyGraph("Neo4j disabled");
		}
		Session session = getDriver().session();
		try {
			List<Record> records = session.run(cypher).list();
			return buildGraph(records);
		} finally {
			session.close();
		}
	}

	/**
	 * 关键词回退查询：按 name/path/logicalPath 模糊匹配节点。
	 */
	public Map<String, Object> queryByKeyword(String keyword) {
		return queryByKeyword(keyword, 80);
	}

	public Map<String, Object> queryByKeyword(String keyword, int limit) {
		if (!neo4jEnabled) {
			return emptyGraph("Neo4j disabled");
		}
		int safeLimit = Math.max(20, Math.min(limit, 500));

		String cypher = "MATCH (n) "
				+ "WHERE toLower(coalesce(n.name,'')) CONTAINS toLower($kw) "
				+ "OR toLower(coalesce(n.path,'')) CONTAINS toLower($kw) "
				+ "OR toLower(coalesce(n.logicalPath,'')) CONTAINS toLower($kw) "
				+ "OR any(k IN coalesce(n.keywords, []) WHERE toLower(toString(k)) CONTAINS toLower($kw)) "
				+ "RETURN n LIMIT $limit";

		Session session = getDriver().session();
		try {
			List<Record> records = session.run(cypher, Values.parameters("kw", keyword, "limit", safeLimit)).list();
			return buildGraph(records);
		} finally {
			session.close();
		}
	}

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

	private void mergePathHierarchy(Transaction tx, List<String> pathChain) {
		for (int i = 0; i < pathChain.size(); i++) {
			String path = pathChain.get(i);
			tx.run("MERGE (p:LogicalPath {path:$path}) "
							+ "ON CREATE SET p.name=$name, p.depth=$depth, p.updatedAt=timestamp() "
							+ "ON MATCH SET p.name=$name, p.depth=$depth, p.updatedAt=timestamp()",
					Values.parameters("path", path, "name", getLeafName(path), "depth", depth(path)));

			if (i > 0) {
				String parent = pathChain.get(i - 1);
				tx.run("MATCH (a:LogicalPath {path:$parent}), (b:LogicalPath {path:$child}) "
								+ "MERGE (a)-[r:CONTAINS]->(b) "
								+ "SET r.updatedAt=timestamp()",
						Values.parameters("parent", parent, "child", path));
			}
		}
	}

	private void mergeAsset(Transaction tx, String logicalPath, String dataType,
							String fileName, String fileFormat, long fileSize, String createTime) {
		tx.run("MERGE (d:DataAsset {logicalPath:$logicalPath}) "
						+ "ON CREATE SET d.dataType=$dataType, d.fileName=$fileName, d.fileFormat=$fileFormat, d.fileSize=$fileSize, d.createTime=$createTime, d.updatedAt=timestamp() "
						+ "ON MATCH SET d.dataType=$dataType, d.fileName=$fileName, d.fileFormat=$fileFormat, d.fileSize=$fileSize, d.createTime=$createTime, d.updatedAt=timestamp()",
				Values.parameters(
						"logicalPath", logicalPath,
						"dataType", dataType,
						"fileName", fileName,
						"fileFormat", fileFormat,
						"fileSize", fileSize,
						"createTime", createTime));
	}

	private void linkAssetToPath(Transaction tx, String logicalPath) {
		String parentPath = parentPathOfData(logicalPath);
		tx.run("MATCH (p:LogicalPath {path:$parentPath}), (d:DataAsset {logicalPath:$logicalPath}) "
						+ "MERGE (p)-[r:HAS_DATA]->(d) "
						+ "SET r.updatedAt=timestamp()",
				Values.parameters("parentPath", parentPath, "logicalPath", logicalPath));
	}

	private void mergeFields(Transaction tx, String logicalPath, List<String> fields, String kind) {
		if (fields == null || fields.isEmpty()) {
			return;
		}
		String fieldKind = (kind == null || kind.trim().isEmpty()) ? "field" : kind;

		for (String field : fields) {
			String name = safe(field);
			if (name.isEmpty()) {
				continue;
			}
			String norm = normalize(name);
			String ukey = fieldKind + "::" + norm;
			tx.run("MATCH (d:DataAsset {logicalPath:$path}) "
							+ "MERGE (f:Field {ukey:$ukey}) "
							+ "ON CREATE SET f.norm=$norm, f.kind=$kind, f.name=$name, f.updatedAt=timestamp() "
							+ "ON MATCH SET f.norm=coalesce(f.norm,$norm), f.kind=coalesce(f.kind,$kind), f.name=coalesce(f.name,$name), f.updatedAt=timestamp() "
							+ "MERGE (d)-[r:HAS_FIELD]->(f) "
							+ "SET r.updatedAt=timestamp()",
					Values.parameters("path", logicalPath, "ukey", ukey, "norm", norm, "kind", fieldKind, "name", name));
		}
	}

	private void mergeEntities(Transaction tx, String logicalPath, List<String> entities) {
		if (entities == null || entities.isEmpty()) {
			return;
		}

		for (String entity : entities) {
			String alias = safe(entity);
			if (alias.isEmpty()) {
				continue;
			}

			String canonical = normalizeDisplay(alias);
			String norm = normalize(canonical);
			if (norm.isEmpty()) {
				continue;
			}

			tx.run("MATCH (d:DataAsset {logicalPath:$path}) "
							+ "MERGE (e:Entity {norm:$norm}) "
							+ "ON CREATE SET e.name=$name, e.updatedAt=timestamp() "
							+ "ON MATCH SET e.updatedAt=timestamp() "
							+ "MERGE (d)-[r:MENTIONS]->(e) "
							+ "SET r.updatedAt=timestamp()",
					Values.parameters("path", logicalPath, "norm", norm, "name", canonical));
		}
	}

	private void mergeSemanticTriples(Transaction tx,
									  String logicalPath,
									  List<MetadataExtractResult.SemanticTriple> triples) {
		if (triples == null || triples.isEmpty()) {
			return;
		}

		for (MetadataExtractResult.SemanticTriple triple : triples) {
			if (triple == null) {
				continue;
			}

			String subjectName = normalizeDisplay(safe(triple.getSubject()));
			String objectName = normalizeDisplay(safe(triple.getObject()));
			if (subjectName.isEmpty() || objectName.isEmpty()) {
				continue;
			}

			String subjectNorm = normalize(subjectName);
			String objectNorm = normalize(objectName);
			if (subjectNorm.isEmpty() || objectNorm.isEmpty()) {
				continue;
			}

			tx.run("MATCH (d:DataAsset {logicalPath:$path}) "
							+ "MERGE (s:Entity {norm:$subjectNorm}) "
							+ "ON CREATE SET s.name=$subjectName, s.updatedAt=timestamp() "
							+ "ON MATCH SET s.updatedAt=timestamp() "
							+ "MERGE (o:Entity {norm:$objectNorm}) "
							+ "ON CREATE SET o.name=$objectName, o.updatedAt=timestamp() "
							+ "ON MATCH SET o.updatedAt=timestamp() "
							+ "MERGE (d)-[dm1:MENTIONS]->(s) "
							+ "SET dm1.updatedAt=timestamp() "
							+ "MERGE (d)-[dm2:MENTIONS]->(o) "
							+ "SET dm2.updatedAt=timestamp()",
					Values.parameters(
							"path", logicalPath,
							"subjectNorm", subjectNorm,
							"subjectName", subjectName,
							"objectNorm", objectNorm,
							"objectName", objectName));
		}
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
				+ "WITH d, pd, coalesce(d.logicalPath, "
				+ "CASE WHEN pd.path IS NULL THEN '' WHEN pd.path = '/' THEN '/' + coalesce(d.name, d.fileName, '') ELSE pd.path + '/' + coalesce(d.name, d.fileName, '') END) AS assetPath "
				+ "WHERE ($path='' OR assetPath STARTS WITH $path OR coalesce(pd.path,'') STARTS WITH $path) "
				+ "AND ($dt='' OR toLower(coalesce(d.dataType,'')) = $dt) "
				+ "AND ($kw='' "
				+ "OR toLower(coalesce(d.name,'')) CONTAINS toLower($kw) "
				+ "OR toLower(coalesce(d.fileName,'')) CONTAINS toLower($kw) "
				+ "OR toLower(assetPath) CONTAINS toLower($kw) "
				+ "OR any(k IN coalesce(d.keywords, []) WHERE toLower(toString(k)) CONTAINS toLower($kw)) "
				+ "OR EXISTS { MATCH (d)-[:HAS_FIELD]->(f:Field) WHERE toLower(coalesce(f.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(f.norm,'')) CONTAINS toLower($kw) } "
				+ "OR EXISTS { MATCH (d)-[:MENTIONS]->(e:Entity) WHERE toLower(coalesce(e.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(e.norm,'')) CONTAINS toLower($kw) }) "
				+ "WITH d ORDER BY coalesce(d.updatedAt, id(d)) DESC LIMIT $assetWindow "
				+ "RETURN id(d) AS id";

		String matchedDirectoryCypher = "MATCH (p:LogicalPath) "
				+ "WHERE $dt='' "
				+ "AND ($path='' OR coalesce(p.path,'') STARTS WITH $path) "
				+ "AND ($kw='' "
				+ "OR toLower(coalesce(p.name,'')) CONTAINS toLower($kw) "
				+ "OR toLower(coalesce(p.path,'')) CONTAINS toLower($kw) "
				+ "OR any(k IN coalesce(p.keywords, []) WHERE toLower(toString(k)) CONTAINS toLower($kw)) "
				+ "OR EXISTS { MATCH (p)-[:MENTIONS]->(e:Entity) WHERE toLower(coalesce(e.name,'')) CONTAINS toLower($kw) OR toLower(coalesce(e.norm,'')) CONTAINS toLower($kw) }) "
				+ "WITH p ORDER BY coalesce(p.updatedAt, id(p)) DESC LIMIT $directoryWindow "
				+ "RETURN id(p) AS id";

		String assetGraphCypher = "MATCH (d:DataAsset) WHERE id(d) IN $assetIds "
				+ "OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d) "
				+ "OPTIONAL MATCH pathChain=(root:LogicalPath {path:'/'})-[:CONTAINS*0..32]->(p) "
				+ "OPTIONAL MATCH (parent:DataAsset)-[ca:CONTAINS_ASSET]->(d) WHERE id(parent) IN $assetIds "
				+ "OPTIONAL MATCH (d)-[ca2:CONTAINS_ASSET]->(child:DataAsset) WHERE id(child) IN $assetIds "
				+ "OPTIONAL MATCH (d)-[sr:SEMANTIC_RELATION]-(related:DataAsset) WHERE id(related) IN $assetIds "
				+ "OPTIONAL MATCH (d)-[hf:HAS_FIELD]->(f:Field) "
				+ "OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity) "
				+ "RETURN pathChain,p,hd,d,parent,ca,ca2,child,sr,related,hf,f,m,e LIMIT $limit";

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
						"limit", safeLimit)).list());
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

	public List<Map<String, Object>> findUncomputedAssetRelationPairs(List<String> assetIds, int limit) {
		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		if (!neo4jEnabled || assetIds == null || assetIds.size() < 2) {
			return out;
		}
		ensureConstraints();
		List<Long> ids = new ArrayList<Long>();
		for (String id : assetIds) {
			try {
				ids.add(Long.valueOf(id));
			} catch (Exception ignore) {
			}
		}
		if (ids.size() < 2) {
			return out;
		}
		int safeLimit = Math.max(1, Math.min(limit, 30));
		String cypher = "MATCH (a:DataAsset) WHERE id(a) IN $ids "
				+ "MATCH (b:DataAsset) WHERE id(b) IN $ids AND id(a) < id(b) "
				+ "WHERE NOT (a)-[:CONTAINS_ASSET]-(b) "
				+ "AND NOT (a)-[:SEMANTIC_RELATION]-(b) "
				+ "RETURN id(a) AS leftId, id(b) AS rightId, "
				+ "coalesce(a.fileName, a.name, a.logicalPath, '') AS leftName, "
				+ "coalesce(b.fileName, b.name, b.logicalPath, '') AS rightName, "
				+ "coalesce(a.keywords, []) AS leftKeywords, coalesce(b.keywords, []) AS rightKeywords "
				+ "LIMIT $limit";
		Session session = getDriver().session();
		try {
			List<Record> records = session.run(cypher, Values.parameters("ids", ids, "limit", safeLimit)).list();
			for (Record record : records) {
				Map<String, Object> row = new LinkedHashMap<String, Object>();
				row.put("leftId", record.get("leftId").asLong());
				row.put("rightId", record.get("rightId").asLong());
				row.put("leftName", record.get("leftName").asString(""));
				row.put("rightName", record.get("rightName").asString(""));
				row.put("leftKeywords", valueAsStringList(record.get("leftKeywords")));
				row.put("rightKeywords", valueAsStringList(record.get("rightKeywords")));
				out.add(row);
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

	public void upsertSemanticRelation(long leftId, long rightId, String relation, String source) {
		if (!neo4jEnabled || leftId == rightId || relation == null || relation.trim().isEmpty()) {
			return;
		}
		ensureConstraints();
		String cypher = "MATCH (a:DataAsset), (b:DataAsset) "
				+ "WHERE id(a)=$leftId AND id(b)=$rightId "
				+ "MERGE (a)-[r:SEMANTIC_RELATION]-(b) "
				+ "SET r.relation=$relation, r.source=$source, r.updatedAt=timestamp()";
		Session session = getDriver().session();
		try {
			session.run(cypher, Values.parameters(
					"leftId", leftId,
					"rightId", rightId,
					"relation", relation.trim(),
					"source", source == null ? "metadata-relation-batch" : source));
		} finally {
			session.close();
		}
	}

	private List<String> valueAsStringList(org.neo4j.driver.Value value) {
		List<String> out = new ArrayList<String>();
		if (value == null || value.isNull()) {
			return out;
		}
		for (Object item : value.asList()) {
			if (item != null) {
				String text = String.valueOf(item).trim();
				if (!text.isEmpty()) {
					out.add(text);
				}
			}
		}
		return out;
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

	private String normalizePath(String path) {
		if (path == null || path.trim().isEmpty()) {
			return "/";
		}
		String p = path.trim();
		if (!p.startsWith("/")) {
			p = "/" + p;
		}
		while (p.length() > 1 && p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		return p;
	}

	private List<String> buildPathChain(String logicalPath) {
		List<String> chain = new ArrayList<String>();
		chain.add("/");
		if ("/".equals(logicalPath)) {
			return chain;
		}
		String[] parts = logicalPath.substring(1).split("/");
		String current = "";
		for (String part : parts) {
			if (part == null || part.trim().isEmpty()) {
				continue;
			}
			current += "/" + part;
			chain.add(current);
		}
		return chain;
	}

	private String parentPathOfData(String logicalPath) {
		if (logicalPath == null || logicalPath.trim().isEmpty()) {
			return "/";
		}
		return normalizePath(logicalPath);
	}

	private String getLeafName(String path) {
		if (path == null || "/".equals(path)) return "/";
		int i = path.lastIndexOf('/');
		return i >= 0 ? path.substring(i + 1) : path;
	}

	private int depth(String path) {
		if (path == null || "/".equals(path)) return 0;
		int d = 0;
		for (int i = 0; i < path.length(); i++) {
			if (path.charAt(i) == '/') d++;
		}
		return d;
	}

	private String normalizeDisplay(String name) {
		if (name == null) return "";
		return name.trim().replaceAll("\\s+", " ");
	}

	private String normalize(String value) {
		if (value == null) return "";
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	@PreDestroy
	public void close() {
		if (driver != null) {
			driver.close();
		}
	}
}
