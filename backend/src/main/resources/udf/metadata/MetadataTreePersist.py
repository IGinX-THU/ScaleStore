from metadata.SemanticKeywordExtract import _RuntimeConfigMixin
from metadata.neo4j_writer import Neo4jGraphWriter


class MetadataTreePersist(_RuntimeConfigMixin):
    def transform(self, data, args, kvargs):
        try:
            params = self._params(kvargs)
            params["skipAncestorKeywordRefresh"] = "true"
            params["skipSemanticEntities"] = "true"
            writer = Neo4jGraphWriter(params)
            count = 0
            for row in self._rows(data):
                if self._safe(row.get("isValid", "true")).lower() == "false":
                    continue
                logical_path = self._normalize_path(row.get("logicalPath", ""))
                data_type = self._safe(row.get("dataType", "")).lower()
                file_name = self._safe(row.get("fileName", ""))
                if not logical_path or not data_type:
                    continue
                params["metaKey"] = self._safe(row.get("key", ""))
                writer.persist(
                    logical_path=logical_path,
                    data_type=data_type,
                    file_name=file_name,
                    file_format="",
                    file_size="0",
                    create_time="",
                    fields=[],
                    field_kind="field",
                    entities=[],
                    triples=[],
                    keywords=self._parse_keywords(row.get("semanticKeywords", "")),
                )
                count += 1
            return self._result("SUCCESS", [], [], "field", "metadata tree persisted: nodes=" + str(count))
        except Exception as exc:
            return self._result("FAILED", [], [], "field", str(exc))

    def _rows(self, data):
        if not isinstance(data, list) or not data:
            return []
        headers = []
        start = 0
        if isinstance(data[0], list):
            candidate = [self._column_name(v) for v in data[0]]
            if any(v in ("logicalPath", "fileName", "dataType") for v in candidate):
                headers = candidate
                start = 2 if len(data) > 1 and isinstance(data[1], list) else 1
        if not headers:
            headers = ["key", "logicalPath", "dataType", "fileName", "contentPath", "fileSize", "fileFormat", "createTime", "isValid", "knowledgeExtractStatus", "semanticKeywords"]
        rows = []
        for raw in data[start:]:
            if not isinstance(raw, list):
                continue
            row = {}
            for idx, header in enumerate(headers):
                if idx < len(raw):
                    row[header] = self._decode(raw[idx])
            rows.append(row)
        return rows

    def _column_name(self, value):
        text = self._decode(value)
        if "." in text:
            text = text.split(".")[-1]
        return text.strip("()")

    def _parse_keywords(self, value):
        import json
        raw = self._safe(value)
        if not raw:
            return []
        try:
            node = json.loads(raw)
            if isinstance(node, list):
                return [str(v).strip() for v in node if str(v).strip()]
        except Exception:
            return []
        return []

    def _normalize_path(self, value):
        path = self._safe(value)
        if not path:
            return "/"
        if not path.startswith("/"):
            path = "/" + path
        while len(path) > 1 and path.endswith("/"):
            path = path[:-1]
        return path
