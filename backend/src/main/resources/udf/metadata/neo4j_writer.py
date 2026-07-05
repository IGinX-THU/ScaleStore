import re


class Neo4jGraphWriter(object):
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
        self._write_path_chain_tx(tx, payload.get("path_chain", []))
        self._delete_legacy_directory_assets_tx(tx, payload)

        if payload.get("data_type", "") == "directory":
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
            REMOVE a.logicalPath, a.dataType, a.fileFormat, a.fileSize, a.createTime, a.assetKind, a.keywords, a.ukey
            """,
            meta_key=meta_key,
            name=payload.get("file_name", ""),
        )
        tx.run(
            """
            MATCH (a:DataAsset {metaKey: $meta_key})-[r:HAS_FIELD]->(:Field)
            DELETE r
            """,
            meta_key=meta_key,
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

        self._delete_legacy_leaf_asset_tx(tx, payload)
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

    def _write_semantic_entities_tx(self, tx, label, key_name, key_value, payload):
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

        terms = []
        terms.extend(payload.get("keywords", []) or [])
        terms.extend(payload.get("entities", []) or [])
        for triple in payload.get("triples", []) or []:
            terms.append(triple.get("subject", ""))
            terms.append(triple.get("object", ""))

        for term in self._dedup_strings(terms, 120):
            name = self._normalize_display(term)
            norm = self._normalize(name)
            if not norm:
                continue
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

    def _delete_legacy_directory_assets_tx(self, tx, payload):
        paths = self._build_path_chain(payload.get("asset_path", ""))
        for path in paths:
            tx.run(
                """
                MATCH (a:DataAsset)
                WHERE a.logicalPath = $path AND (a.dataType = 'directory' OR a.assetKind = 'directory')
                DETACH DELETE a
                """,
                path=path,
            )

    def _delete_legacy_leaf_asset_tx(self, tx, payload):
        tx.run(
            """
            MATCH (a:DataAsset)
            WHERE a.ukey = $legacy_key OR (a.logicalPath = $asset_path AND a.metaKey IS NULL)
            DETACH DELETE a
            """,
            legacy_key="asset::" + payload.get("asset_path", "") + "::" + payload.get("file_name", ""),
            asset_path=payload.get("asset_path", ""),
        )

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
