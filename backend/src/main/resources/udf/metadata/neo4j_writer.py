import re


class Neo4jGraphWriter(object):
    # 约束在同一 Python 进程中只初始化一次，避免每个资产写入都重复执行 DDL。
    _constraints_initialized = False

    def __init__(self, params):
        self.params = params or {}

    def persist(self, logical_path, data_type, file_name, file_format, file_size, create_time, fields, field_kind, entities, triples, keywords=None):
        enabled = str(self.params.get("neo4jEnabled", "true")).strip().lower() == "true"
        if not enabled:
            return "neo4j disabled"

        uri = self._safe(self.params.get("neo4jUri", ""))
        username = self._safe(self.params.get("neo4jUsername", ""))
        password = self._safe(self.params.get("neo4jPassword", ""))
        if not uri or not username or not password:
            raise RuntimeError("neo4j config missing: neo4jUri/neo4jUsername/neo4jPassword")

        try:
            from neo4j import GraphDatabase
        except Exception as exc:
            raise RuntimeError("neo4j package is required: pip install neo4j==4.4.41") from exc

        # DataAsset 的稳定身份由 storage.meta.key 决定；路径仅用于构建目录树和父子关系。
        data_type = self._safe(data_type).lower()
        file_name = self._safe(file_name)
        asset_path = self._asset_path(logical_path, file_name, data_type)
        semantic_terms = self._dedup_strings(
            keywords if keywords is not None else self._keywords_from_semantics(fields, entities),
            80,
        )

        payload = {
            "meta_key": self._safe(self.params.get("metaKey", "")),
            "asset_key": self._asset_key(asset_path, file_name),
            "asset_path": asset_path,
            "parent_path": self._parent_path(asset_path) or "/",
            "data_type": data_type,
            "file_name": file_name or self._leaf_name(asset_path),
            "path_chain": self._build_path_chain(asset_path if data_type == "directory" else (self._parent_path(asset_path) or "/")),
            "keywords": semantic_terms,
            "entities": self._dedup_strings(entities, 120),
            "triples": self._dedup_triples(triples, 180),
        }

        driver = GraphDatabase.driver(uri, auth=(username, password))
        try:
            with driver.session() as session:
                self._ensure_constraints(session)
                session.write_transaction(self._write_graph_tx, payload)
        finally:
            driver.close()

        return "neo4j persisted"

    def persist_tree_batch(self, records, batch_size=500):
        """Persist a directory tree using one driver/session for the complete UDF input."""
        enabled = str(self.params.get("neo4jEnabled", "true")).strip().lower() == "true"
        if not enabled:
            return "neo4j disabled"

        uri = self._safe(self.params.get("neo4jUri", ""))
        username = self._safe(self.params.get("neo4jUsername", ""))
        password = self._safe(self.params.get("neo4jPassword", ""))
        if not uri or not username or not password:
            raise RuntimeError("neo4j config missing: neo4jUri/neo4jUsername/neo4jPassword")

        tree_data = self._build_tree_batch_data(records)
        if not tree_data["paths"]:
            return "neo4j persisted"

        try:
            from neo4j import GraphDatabase
        except Exception as exc:
            raise RuntimeError("neo4j package is required: pip install neo4j==4.4.41") from exc

        safe_batch_size = max(1, int(batch_size or 500))
        driver = GraphDatabase.driver(uri, auth=(username, password))
        try:
            with driver.session() as session:
                self._ensure_constraints(session)
                self._write_tree_batches(session, self._write_tree_paths_tx, tree_data["paths"], safe_batch_size)
                self._write_tree_batches(session, self._write_tree_contains_tx, tree_data["contains"], safe_batch_size)
                self._write_tree_batches(session, self._write_tree_directories_tx, tree_data["directories"], safe_batch_size)
                self._write_tree_batches(session, self._write_tree_assets_tx, tree_data["assets"], safe_batch_size)
        finally:
            driver.close()

        return "neo4j persisted"

    def _build_tree_batch_data(self, records):
        paths = {}
        contains = {}
        directories = {}
        assets = {}

        for record in records or []:
            logical_path = self._normalize_path(record.get("logicalPath", ""))
            data_type = self._safe(record.get("dataType", "")).lower()
            file_name = self._safe(record.get("fileName", ""))
            meta_key = self._safe(record.get("metaKey", ""))
            if not logical_path or not data_type:
                continue

            asset_path = self._asset_path(logical_path, file_name, data_type)
            chain_target = asset_path if data_type == "directory" else (self._parent_path(asset_path) or "/")
            path_chain = self._build_path_chain(chain_target)
            for path in path_chain:
                paths[path] = {
                    "path": path,
                    "name": self._leaf_name(path),
                    "depth": self._depth(path),
                }
            for idx in range(1, len(path_chain)):
                parent_path = path_chain[idx - 1]
                child_path = path_chain[idx]
                contains[parent_path + "\n" + child_path] = {
                    "parentPath": parent_path,
                    "childPath": child_path,
                }

            if data_type == "directory":
                if meta_key:
                    directories[asset_path] = {
                        "path": asset_path,
                        "metaKey": meta_key,
                    }
                continue

            if not meta_key:
                raise RuntimeError("metaKey is required for DataAsset graph persistence")
            assets[meta_key] = {
                "metaKey": meta_key,
                "parentPath": self._parent_path(asset_path) or "/",
                "name": file_name or self._leaf_name(asset_path),
                "dataType": data_type,
                "keywords": self._dedup_strings(record.get("keywords", []), 80),
            }

        return {
            "paths": list(paths.values()),
            "contains": list(contains.values()),
            "directories": list(directories.values()),
            "assets": list(assets.values()),
        }

    def _write_tree_batches(self, session, writer, rows, batch_size):
        for start in range(0, len(rows), batch_size):
            session.write_transaction(writer, rows[start:start + batch_size])

    def _write_tree_paths_tx(self, tx, rows):
        tx.run(
            """
            UNWIND $rows AS row
            MERGE (p:LogicalPath {path: row.path})
            ON CREATE SET p.name = row.name,
                          p.depth = row.depth,
                          p.updatedAt = timestamp()
            """,
            rows=rows,
        )

    def _write_tree_contains_tx(self, tx, rows):
        tx.run(
            """
            UNWIND $rows AS row
            MATCH (parent:LogicalPath {path: row.parentPath})
            MATCH (child:LogicalPath {path: row.childPath})
            MERGE (parent)-[r:CONTAINS]->(child)
            ON CREATE SET r.updatedAt = timestamp()
            """,
            rows=rows,
        )

    def _write_tree_directories_tx(self, tx, rows):
        tx.run(
            """
            UNWIND $rows AS row
            MATCH (p:LogicalPath {path: row.path})
            WHERE p.metaKey IS NULL OR p.metaKey <> row.metaKey
            SET p.metaKey = row.metaKey,
                p.updatedAt = timestamp()
            """,
            rows=rows,
        )

    def _write_tree_assets_tx(self, tx, rows):
        tx.run(
            """
            UNWIND $rows AS row
            MERGE (a:DataAsset {metaKey: row.metaKey})
            ON CREATE SET a.name = row.name,
                          a.updatedAt = timestamp()
            ON MATCH SET a.name = row.name,
                         a.updatedAt = timestamp()
            SET a.dataType = row.dataType,
                a.semanticKeywords = row.keywords
            REMOVE a.logicalPath, a.fileFormat, a.fileSize, a.createTime, a.assetKind, a.keywords, a.ukey
            """,
            rows=rows,
        )
        tx.run(
            """
            UNWIND $rows AS row
            MATCH (p:LogicalPath {path: row.parentPath})
            MATCH (a:DataAsset {metaKey: row.metaKey})
            MERGE (p)-[r:HAS_DATA]->(a)
            ON CREATE SET r.updatedAt = timestamp()
            """,
            rows=rows,
        )

    def _ensure_constraints(self, session):
        if Neo4jGraphWriter._constraints_initialized:
            return

        statements = [
            "CREATE CONSTRAINT logical_path_unique IF NOT EXISTS FOR (p:LogicalPath) REQUIRE p.path IS UNIQUE",
            "CREATE CONSTRAINT data_asset_meta_key_unique IF NOT EXISTS FOR (a:DataAsset) REQUIRE a.metaKey IS UNIQUE",
            "CREATE CONSTRAINT entity_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.norm IS UNIQUE",
        ]
        for stmt in statements:
            session.run(stmt)

        Neo4jGraphWriter._constraints_initialized = True

    def _write_graph_tx(self, tx, payload):
        # 路径节点先于资产节点创建，以确保 HAS_DATA 关系总能找到父目录。
        self._write_path_chain_tx(tx, payload.get("path_chain", []))

        if payload.get("data_type", "") == "directory":
            # 目录本身映射为 LogicalPath，不额外创建 DataAsset，避免一个路径有两种图表示。
            self._write_directory_meta_key_tx(tx, payload)
            if not str(self.params.get("skipSemanticEntities", "")).strip().lower() == "true":
                self._write_semantic_entities_tx(tx, "LogicalPath", "path", payload.get("asset_path", ""), payload)
            return

        meta_key = payload.get("meta_key", "")
        if not meta_key:
            raise RuntimeError("metaKey is required for DataAsset graph persistence")

        tx.run(
            """
            MERGE (a:DataAsset {metaKey: $meta_key})
            ON CREATE SET a.name = $name, a.updatedAt = timestamp()
            ON MATCH SET a.name = $name,
                         a.updatedAt = timestamp()
            SET a.dataType = $data_type,
                a.semanticKeywords = $keywords
            REMOVE a.logicalPath, a.fileFormat, a.fileSize, a.createTime, a.assetKind, a.keywords, a.ukey
            """,
            meta_key=meta_key,
            name=payload.get("file_name", ""),
            data_type=payload.get("data_type", ""),
            keywords=payload.get("keywords", []),
        )
        tx.run(
            """
            MATCH (p:LogicalPath {path: $parent_path})
            MATCH (a:DataAsset {metaKey: $meta_key})
            MERGE (p)-[r:HAS_DATA]->(a)
            SET r.updatedAt = timestamp()
            """,
            parent_path=payload.get("parent_path", "/"),
            meta_key=meta_key,
        )

        if not str(self.params.get("skipSemanticEntities", "")).strip().lower() == "true":
            self._write_semantic_entities_tx(tx, "DataAsset", "metaKey", meta_key, payload)

    def _write_path_chain_tx(self, tx, path_chain):
        for idx, path in enumerate(path_chain or ["/"]):
            tx.run(
                """
                MERGE (p:LogicalPath {path: $path})
                ON CREATE SET p.name = $name, p.depth = $depth, p.updatedAt = timestamp()
                ON MATCH SET p.name = $name,
                             p.depth = $depth,
                             p.updatedAt = timestamp()
                REMOVE p.keywords
                """,
                path=path,
                name=self._leaf_name(path),
                depth=self._depth(path),
            )
            if idx > 0:
                tx.run(
                    """
                    MATCH (parent:LogicalPath {path: $parent_path})
                    MATCH (child:LogicalPath {path: $child_path})
                    MERGE (parent)-[r:CONTAINS]->(child)
                    SET r.updatedAt = timestamp()
                    """,
                    parent_path=path_chain[idx - 1],
                    child_path=path,
                )

    def _write_directory_meta_key_tx(self, tx, payload):
        meta_key = payload.get("meta_key", "")
        asset_path = payload.get("asset_path", "")
        if not meta_key or not asset_path:
            return
        tx.run(
            """
            MATCH (p:LogicalPath {path: $asset_path})
            SET p.metaKey = $meta_key,
                p.updatedAt = timestamp()
            """,
            asset_path=asset_path,
            meta_key=meta_key,
        )

    def _write_semantic_entities_tx(self, tx, label, key_name, key_value, payload):
        # 语义提取是覆盖式快照：先删除当前资产旧的 MENTIONS 和资产范围关系，再写入本次结果。
        tx.run(
            """
            MATCH (n)
            WHERE $label IN labels(n) AND n[$key_name] = $key_value
            OPTIONAL MATCH (n)-[r:MENTIONS]->(:Entity)
            DELETE r
            """,
            label=label,
            key_name=key_name,
            key_value=key_value,
        )
        tx.run(
            """
            MATCH ()-[r:SEMANTIC_RELATION]->()
            WHERE r.assetKey = $asset_key
            DELETE r
            """,
            asset_key=self._relation_asset_key(label, key_value),
        )

        keyword_terms = self._dedup_strings(payload.get("keywords", []) or [], 120)
        keyword_norms = set()
        for term in keyword_terms:
            name = self._normalize_display(term)
            norm = self._normalize(name)
            if not norm:
                continue
            keyword_norms.add(norm)
            tx.run(
                """
                MATCH (n)
                WHERE $label IN labels(n) AND n[$key_name] = $key_value
                MERGE (e:Entity {norm: $norm})
                ON CREATE SET e.name = $name, e.updatedAt = timestamp()
                ON MATCH SET e.name = coalesce(e.name, $name),
                             e.updatedAt = timestamp()
                MERGE (n)-[r:MENTIONS]->(e)
                SET r.updatedAt = timestamp()
                """,
                label=label,
                key_name=key_name,
                key_value=key_value,
                norm=norm,
                name=name,
            )

        for triple in payload.get("triples", []) or []:
            # 图关系端点必须属于本次关键词集合，阻止模型返回的外部或幻觉实体进入知识图谱。
            subject = self._normalize_display(triple.get("subject", ""))
            predicate = self._normalize_display(triple.get("predicate", triple.get("relation", "")))
            obj = self._normalize_display(triple.get("object", ""))
            subject_norm = self._normalize(subject)
            object_norm = self._normalize(obj)
            if (not subject_norm or not object_norm or subject_norm == object_norm or not predicate
                    or subject_norm not in keyword_norms or object_norm not in keyword_norms):
                continue
            subject_norm, subject, object_norm, obj = self._canonical_entity_pair(
                subject_norm, subject, object_norm, obj
            )
            tx.run(
                """
                MERGE (subject:Entity {norm: $subject_norm})
                ON CREATE SET subject.name = $subject, subject.updatedAt = timestamp()
                ON MATCH SET subject.name = coalesce(subject.name, $subject), subject.updatedAt = timestamp()
                MERGE (object:Entity {norm: $object_norm})
                ON CREATE SET object.name = $object, object.updatedAt = timestamp()
                ON MATCH SET object.name = coalesce(object.name, $object), object.updatedAt = timestamp()
                MERGE (subject)-[r:SEMANTIC_RELATION {assetKey: $asset_key}]->(object)
                SET r.related = true,
                    r.relation = $relation,
                    r.source = 'asset-extraction',
                    r.updatedAt = timestamp()
                """,
                subject_norm=subject_norm,
                subject=subject,
                object_norm=object_norm,
                object=obj,
                asset_key=self._relation_asset_key(label, key_value),
                relation=predicate,
            )

    def persist_entity_relation(self, subject, relation, obj, asset_key, related=True, source="query-udf"):
        enabled = str(self.params.get("neo4jEnabled", "true")).strip().lower() == "true"
        if not enabled:
            return "neo4j disabled"

        uri = self._safe(self.params.get("neo4jUri", ""))
        username = self._safe(self.params.get("neo4jUsername", ""))
        password = self._safe(self.params.get("neo4jPassword", ""))
        if not uri or not username or not password:
            raise RuntimeError("neo4j config missing: neo4jUri/neo4jUsername/neo4jPassword")

        subject_name = self._normalize_display(subject)
        object_name = self._normalize_display(obj)
        relation_name = self._normalize_display(relation)
        relation_asset_key = self._safe(asset_key)
        subject_norm = self._normalize(subject_name)
        object_norm = self._normalize(object_name)
        if not subject_norm or not object_norm or subject_norm == object_norm or not relation_name or not relation_asset_key:
            return "entity relation skipped"
        # 实体对按规范名排序，使无向语义判断在 Neo4j 中始终落到同一条有向边上。
        subject_norm, subject_name, object_norm, object_name = self._canonical_entity_pair(
            subject_norm, subject_name, object_norm, object_name
        )

        try:
            from neo4j import GraphDatabase
        except Exception as exc:
            raise RuntimeError("neo4j package is required: pip install neo4j==4.4.41") from exc

        driver = GraphDatabase.driver(uri, auth=(username, password))
        try:
            with driver.session() as session:
                self._ensure_constraints(session)
                session.write_transaction(
                    self._write_entity_relation_tx,
                    subject_norm,
                    subject_name,
                    relation_name,
                    object_norm,
                    object_name,
                    relation_asset_key,
                    bool(related),
                    self._safe(source) or "query-udf",
                )
        finally:
            driver.close()
        return "entity relation persisted"

    def _write_entity_relation_tx(self, tx, subject_norm, subject, relation, object_norm, obj, asset_key, related, source):
        tx.run(
            """
            MERGE (subject:Entity {norm: $subject_norm})
            ON CREATE SET subject.name = $subject, subject.updatedAt = timestamp()
            ON MATCH SET subject.name = coalesce(subject.name, $subject), subject.updatedAt = timestamp()
            MERGE (object:Entity {norm: $object_norm})
            ON CREATE SET object.name = $object, object.updatedAt = timestamp()
            ON MATCH SET object.name = coalesce(object.name, $object), object.updatedAt = timestamp()
            MERGE (subject)-[r:SEMANTIC_RELATION {assetKey: $asset_key}]->(object)
            SET r.related = $related,
                r.relation = $relation,
                r.source = $source,
                r.updatedAt = timestamp()
            """,
            subject_norm=subject_norm,
            subject=subject,
            relation=relation,
            object_norm=object_norm,
            object=obj,
            asset_key=asset_key,
            related=related,
            source=source,
        )

    def get_entity_relation_statuses(self, focus, candidates, asset_key):
        """Return cached true/false conclusions for one asset-scoped entity set."""
        enabled = str(self.params.get("neo4jEnabled", "true")).strip().lower() == "true"
        if not enabled:
            return {}

        uri = self._safe(self.params.get("neo4jUri", ""))
        username = self._safe(self.params.get("neo4jUsername", ""))
        password = self._safe(self.params.get("neo4jPassword", ""))
        focus_norm = self._normalize(focus)
        # 查询同样以资产为作用域；其他资产的 related=false 不能影响当前资产的判断。
        candidate_norms = self._dedup_strings([self._normalize(item) for item in candidates], 120)
        relation_asset_key = self._safe(asset_key)
        if not uri or not username or not password:
            raise RuntimeError("neo4j config missing: neo4jUri/neo4jUsername/neo4jPassword")
        if not focus_norm or not candidate_norms or not relation_asset_key:
            return {}

        try:
            from neo4j import GraphDatabase
        except Exception as exc:
            raise RuntimeError("neo4j package is required: pip install neo4j==4.4.41") from exc

        driver = GraphDatabase.driver(uri, auth=(username, password))
        try:
            with driver.session() as session:
                self._ensure_constraints(session)
                records = session.read_transaction(
                    self._read_entity_relation_statuses_tx,
                    focus_norm,
                    candidate_norms,
                    relation_asset_key,
                )
        finally:
            driver.close()

        statuses = {}
        for record in records:
            norm = self._safe(record.get("norm", ""))
            related = record.get("related")
            if norm and isinstance(related, bool):
                statuses[norm] = related
        return statuses

    def _read_entity_relation_statuses_tx(self, tx, focus_norm, candidate_norms, asset_key):
        result = tx.run(
            """
            MATCH (focus:Entity {norm: $focus_norm})-[r:SEMANTIC_RELATION]-(candidate:Entity)
            WHERE r.assetKey = $asset_key
              AND candidate.norm IN $candidate_norms
              AND r.related IS NOT NULL
            RETURN candidate.norm AS norm, r.related AS related, r.updatedAt AS updatedAt
            ORDER BY updatedAt DESC
            """,
            focus_norm=focus_norm,
            candidate_norms=candidate_norms,
            asset_key=asset_key,
        )
        return list(result)

    def _relation_asset_key(self, label, key_value):
        return self._safe(label) + "::" + self._safe(key_value)

    def _canonical_entity_pair(self, left_norm, left_name, right_norm, right_name):
        if left_norm <= right_norm:
            return left_norm, left_name, right_norm, right_name
        return right_norm, right_name, left_norm, left_name

    def _normalize_path(self, logical_path):
        path = self._safe(logical_path)
        if not path:
            return "/"
        if not path.startswith("/"):
            path = "/" + path
        while len(path) > 1 and path.endswith("/"):
            path = path[:-1]
        return path

    def _asset_path(self, logical_path, file_name, data_type):
        path = self._normalize_path(logical_path)
        fn = self._safe(file_name)
        if fn and not path.endswith("/" + fn):
            return self._normalize_path(path + "/" + fn)
        return path

    def _asset_key(self, asset_path, file_name):
        return self._normalize_path(asset_path) + "::" + self._safe(file_name)

    def _build_path_chain(self, logical_path):
        path = self._normalize_path(logical_path)
        chain = ["/"]
        if path == "/":
            return chain
        current = ""
        for part in path[1:].split("/"):
            if not part:
                continue
            current += "/" + part
            chain.append(current)
        return chain

    def _parent_path(self, path):
        p = self._normalize_path(path)
        if p == "/":
            return ""
        idx = p.rfind("/")
        if idx <= 0:
            return "/"
        return p[:idx]

    def _leaf_name(self, path):
        p = self._normalize_path(path)
        if p == "/":
            return "/"
        return p[p.rfind("/") + 1:]

    def _depth(self, path):
        p = self._normalize_path(path)
        if p == "/":
            return 0
        return p.count("/")

    def _normalize_display(self, value):
        return re.sub(r"\s+", " ", self._safe(value))

    def _normalize(self, token):
        txt = self._safe(token).lower()
        txt = re.sub(r"[^a-z0-9\u4e00-\u9fa5]", "", txt)
        return txt

    def _dedup_strings(self, values, max_count):
        seen = set()
        out = []
        for value in values or []:
            text = self._safe(value)
            if not text or text in seen:
                continue
            seen.add(text)
            out.append(text)
            if len(out) >= max_count:
                break
        return out

    def _dedup_triples(self, triples, max_count):
        seen = set()
        out = []
        for triple in triples or []:
            if not isinstance(triple, dict):
                continue
            subject = self._safe(triple.get("subject", ""))
            predicate = self._safe(triple.get("predicate", triple.get("relation", "")))
            obj = self._safe(triple.get("object", ""))
            if not subject or not predicate or not obj:
                continue
            key = subject + "|" + predicate + "|" + obj
            if key in seen:
                continue
            seen.add(key)
            out.append({"subject": subject, "predicate": predicate, "object": obj})
            if len(out) >= max_count:
                break
        return out

    def _keywords_from_semantics(self, fields, entities):
        out = []
        for value in fields or []:
            text = self._safe(value)
            if text:
                out.append(text)
        for value in entities or []:
            text = self._safe(value)
            if text:
                out.append(text)
        return out

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()
