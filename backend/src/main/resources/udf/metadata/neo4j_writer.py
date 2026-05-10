import re


class Neo4jGraphWriter(object):
    _constraints_initialized = False

    def __init__(self, params):
        self.params = params or {}

    def persist(self, logical_path, data_type, file_name, file_format, file_size, create_time, fields, field_kind, entities, triples):
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

        asset_path = self._normalize_path(logical_path)

        payload = {
            "asset_path": asset_path,
            "asset_ukey": self._asset_ukey(asset_path, self._safe(file_name)),
            "data_type": self._safe(data_type),
            "file_name": self._safe(file_name),
            "file_format": self._safe(file_format),
            "file_size": self._to_int(file_size),
            "create_time": self._safe(create_time),
            "fields": self._dedup_strings(fields, 120),
            "field_kind": self._safe(field_kind) or "field",
            "entities": self._dedup_strings(entities, 120),
            "triples": self._dedup_triples(triples, 180),
            "path_chain": self._build_path_chain(asset_path),
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
            "DROP CONSTRAINT data_asset_unique IF EXISTS",
            "CREATE CONSTRAINT data_asset_ukey_unique IF NOT EXISTS FOR (a:DataAsset) REQUIRE a.ukey IS UNIQUE",
            "CREATE CONSTRAINT field_unique IF NOT EXISTS FOR (f:Field) REQUIRE f.ukey IS UNIQUE",
            "CREATE CONSTRAINT entity_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.norm IS UNIQUE",
        ]

        for stmt in statements:
            session.run(stmt)

        Neo4jGraphWriter._constraints_initialized = True

    def _write_graph_tx(self, tx, payload):
        asset_path = payload.get("asset_path", "")
        path_chain = payload.get("path_chain", [])

        for idx, path in enumerate(path_chain):
            tx.run(
                """
                MERGE (p:LogicalPath {path: $path})
                ON CREATE SET p.name = $name, p.depth = $depth, p.updatedAt = timestamp()
                ON MATCH SET p.name = $name, p.depth = $depth, p.updatedAt = timestamp()
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

        tx.run(
            """
            MERGE (a:DataAsset {ukey: $asset_ukey})
            ON CREATE SET a.logicalPath = $logical_path,
                          a.dataType = $data_type,
                          a.fileName = $file_name,
                          a.fileFormat = $file_format,
                          a.fileSize = $file_size,
                          a.createTime = $create_time,
                          a.updatedAt = timestamp()
            ON MATCH SET a.logicalPath = $logical_path,
                         a.dataType = $data_type,
                         a.fileName = $file_name,
                         a.fileFormat = $file_format,
                         a.fileSize = $file_size,
                         a.createTime = $create_time,
                         a.updatedAt = timestamp()
            """,
            asset_ukey=payload.get("asset_ukey", ""),
            logical_path=asset_path,
            file_name=payload.get("file_name", ""),
            file_format=payload.get("file_format", ""),
            file_size=payload.get("file_size", 0),
            create_time=payload.get("create_time", ""),
            data_type=payload.get("data_type", ""),
        )

        parent_path = asset_path
        tx.run(
            """
            MATCH (p:LogicalPath {path: $parent_path}), (a:DataAsset {ukey: $asset_ukey})
            MERGE (p)-[r:HAS_DATA]->(a)
            SET r.updatedAt = timestamp()
            """,
            parent_path=parent_path,
            asset_ukey=payload.get("asset_ukey", ""),
        )

        # Keep the graph model clean for structured data: no Domain nodes/links.
        tx.run(
            """
            MATCH ()-[r:IN_DOMAIN]->(:Domain)
            DELETE r
            """
        )
        tx.run(
            """
            MATCH (d:Domain)
            DETACH DELETE d
            """
        )

        for field in payload.get("fields", []):
            field_name = self._safe(field)
            if not field_name:
                continue
            field_kind = payload.get("field_kind", "field")
            norm = self._normalize(field_name)
            if not norm:
                continue
            field_ukey = field_kind + "::" + norm
            tx.run(
                """
                MATCH (a:DataAsset {ukey: $asset_ukey})
                MERGE (f:Field {ukey: $field_ukey})
                ON CREATE SET f.norm = $norm, f.kind = $field_kind, f.name = $field_name, f.updatedAt = timestamp()
                ON MATCH SET f.norm = coalesce(f.norm, $norm),
                             f.kind = coalesce(f.kind, $field_kind),
                             f.name = coalesce(f.name, $field_name),
                             f.updatedAt = timestamp()
                MERGE (a)-[r:HAS_FILED]->(f)
                SET r.updatedAt = timestamp()
                """,
                asset_ukey=payload.get("asset_ukey", ""),
                field_ukey=field_ukey,
                norm=norm,
                field_name=field_name,
                field_kind=field_kind,
            )

        for entity in payload.get("entities", []):
            entity_alias = self._safe(entity)
            if not entity_alias:
                continue
            entity_name = self._normalize_display(entity_alias)
            entity_norm = self._normalize(entity_name)
            if not entity_norm:
                continue
            tx.run(
                """
                MATCH (a:DataAsset {ukey: $asset_ukey})
                MERGE (e:Entity {norm: $entity_norm})
                ON CREATE SET e.name = $entity_name, e.updatedAt = timestamp()
                ON MATCH SET e.updatedAt = timestamp()
                MERGE (a)-[r:MENTIONS]->(e)
                SET r.updatedAt = timestamp()
                """,
                asset_ukey=payload.get("asset_ukey", ""),
                entity_norm=entity_norm,
                entity_name=entity_name,
            )

        for triple in payload.get("triples", []):
            subject_name = self._normalize_display(self._safe(triple.get("subject", "")))
            relation_text = self._normalize_display(self._safe(triple.get("predicate", triple.get("relation", ""))))
            object_name = self._normalize_display(self._safe(triple.get("object", "")))
            if not subject_name or not relation_text or not object_name:
                continue

            subject_norm = self._normalize(subject_name)
            object_norm = self._normalize(object_name)
            if not subject_norm or not object_norm:
                continue

            tx.run(
                """
                MATCH (a:DataAsset {ukey: $asset_ukey})
                MERGE (s:Entity {norm: $subject_norm})
                ON CREATE SET s.name = $subject_name, s.updatedAt = timestamp()
                ON MATCH SET s.updatedAt = timestamp()
                MERGE (o:Entity {norm: $object_norm})
                ON CREATE SET o.name = $object_name, o.updatedAt = timestamp()
                ON MATCH SET o.updatedAt = timestamp()
                MERGE (a)-[r1:MENTIONS]->(s)
                SET r1.updatedAt = timestamp()
                MERGE (a)-[r2:MENTIONS]->(o)
                SET r2.updatedAt = timestamp()
                MERGE (s)-[r:SEMANTIC_RELATION {relation: $relation, sourcePath: $logical_path}]->(o)
                SET r.updatedAt = timestamp()
                """,
                asset_ukey=payload.get("asset_ukey", ""),
                logical_path=asset_path,
                subject_norm=subject_norm,
                subject_name=subject_name,
                object_norm=object_norm,
                object_name=object_name,
                relation=relation_text,
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

    def _build_path_chain(self, logical_path):
        chain = ["/"]
        if logical_path == "/":
            return chain

        parts = logical_path[1:].split("/")
        current = ""
        for part in parts:
            if not part:
                continue
            current += "/" + part
            chain.append(current)
        return chain

    def _asset_ukey(self, logical_path, file_name):
        lp = self._normalize_path(logical_path)
        fn = self._safe(file_name)
        return lp + "::" + fn

    def _leaf_name(self, path):
        if not path or path == "/":
            return "/"
        idx = path.rfind("/")
        return path[idx + 1:] if idx >= 0 else path

    def _depth(self, path):
        if not path or path == "/":
            return 0
        return path.count("/")

    def _normalize_display(self, value):
        if value is None:
            return ""
        return re.sub(r"\s+", " ", str(value).strip())

    def _normalize(self, token):
        txt = self._safe(token).lower()
        txt = re.sub(r"[^a-z0-9\u4e00-\u9fa5]", "", txt)
        return txt

    def _to_int(self, value):
        try:
            return int(str(value).strip())
        except Exception:
            return 0

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

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()
