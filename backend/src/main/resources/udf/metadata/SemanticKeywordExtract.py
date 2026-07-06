import json
import os
import re

from metadata.extractors.document_extractor import DocumentMetadataExtractor
from metadata.extractors.file_extractor import FileMetadataExtractor
from metadata.extractors.keyvalue_extractor import KeyValueMetadataExtractor
from metadata.extractors.relational_extractor import RelationalMetadataExtractor
from metadata.extractors.timeseries_extractor import TimeSeriesMetadataExtractor
from metadata.neo4j_writer import Neo4jGraphWriter


def normalize_asset_keywords(params, asset, fallback=None, max_count=12):
    fallback_keywords = dedup_strings(fallback if fallback is not None else _asset_fallback(asset), max_count)
    if not _llm_available(params):
        return fallback_keywords

    prompt = (
        "You are a metadata semantic keyword assistant. Return strict JSON only: "
        "{\"keywords\":[\"中文关键词\"]}. "
        "Summarize the asset into concise Chinese keywords. Translate English column, field, key, "
        "file, table, metric, and business names into natural Chinese whenever possible. "
        "Do not return raw English identifiers unless they are unavoidable product names or abbreviations."
    )
    user_content = json.dumps({
        "asset": {
            "logicalPath": _safe(asset.get("logicalPath", "")),
            "fileName": _safe(asset.get("fileName", "")),
            "dataType": _safe(asset.get("dataType", "")),
            "fieldKind": _safe(asset.get("fieldKind", "field")),
            "fields": dedup_strings(asset.get("fields", []) or [], 120),
            "entities": dedup_strings(asset.get("entities", []) or [], 80),
            "rawKeywords": fallback_keywords,
        },
        "rules": [
            "Only output Chinese semantic short phrases.",
            "Prefer domain concepts, measurements, devices, business objects, and document topics.",
            "Merge similar identifiers instead of translating word by word.",
            "Return 3 to 12 keywords when possible.",
        ],
    }, ensure_ascii=False)

    try:
        node = _parse_json_object(_llm_chat(params, _safe(params.get("llmModel", "")), [
            {"role": "system", "content": prompt},
            {"role": "user", "content": user_content},
        ]))
        keywords = node.get("keywords", []) if isinstance(node, dict) else []
        normalized = dedup_strings([v for v in keywords if isinstance(v, str)], max_count)
        return normalized or fallback_keywords
    except Exception:
        return fallback_keywords


def summarize_directory_keywords(params, path, child_keywords, max_count=12):
    fallback_keywords = dedup_strings(child_keywords or [], max_count)
    if not fallback_keywords or not _llm_available(params):
        return fallback_keywords

    prompt = (
        "You are a metadata catalog summarization assistant. Return strict JSON only: "
        "{\"keywords\":[\"中文关键词\"]}. "
        "Given child asset keywords, summarize this directory as concise Chinese semantic keywords. "
        "Merge duplicates and higher-level concepts. Do not output raw English identifiers unless unavoidable."
    )
    user_content = json.dumps({
        "directory": _safe(path),
        "childKeywords": dedup_strings(child_keywords, 80),
        "rules": [
            "Return 3 to 12 Chinese short phrases.",
            "Describe the common topic of the child assets.",
            "Do not invent concepts unsupported by the child keywords.",
        ],
    }, ensure_ascii=False)

    try:
        node = _parse_json_object(_llm_chat(params, _safe(params.get("llmModel", "")), [
            {"role": "system", "content": prompt},
            {"role": "user", "content": user_content},
        ]))
        keywords = node.get("keywords", []) if isinstance(node, dict) else []
        normalized = dedup_strings([v for v in keywords if isinstance(v, str)], max_count)
        return normalized or fallback_keywords
    except Exception:
        return fallback_keywords


def dedup_strings(values, max_count):
    seen = set()
    out = []
    for value in values or []:
        text = _safe(value)
        if not text or text in seen:
            continue
        seen.add(text)
        out.append(text)
        if len(out) >= max_count:
            break
    return out


def _asset_fallback(asset):
    values = []
    values.extend(asset.get("keywords", []) or [])
    values.extend(asset.get("entities", []) or [])
    values.extend(asset.get("fields", []) or [])
    if asset.get("fileName"):
        values.append(asset.get("fileName"))
    return values


def _llm_available(params):
    if str(params.get("llmEnabled", "true")).strip().lower() != "true":
        return False
    return bool(_safe(params.get("llmApiKey", "")) and _safe(params.get("llmModel", "")))


def _llm_chat(params, model, messages):
    try:
        from openai import OpenAI
    except Exception as exc:
        raise RuntimeError("openai package is required: pip install openai") from exc

    api_key = _safe(params.get("llmApiKey", ""))
    base_url = _normalize_openai_base_url(_safe(params.get("llmBaseUrl", "")))
    client = OpenAI(api_key=api_key, base_url=base_url) if base_url else OpenAI(api_key=api_key)
    response = client.chat.completions.create(model=model, temperature=0.1, messages=messages)
    if response and response.choices and response.choices[0].message:
        return _extract_text(response.choices[0].message.content)
    return ""


def _parse_json_object(text):
    cleaned = _strip_code_fence(_strip_think(text or ""))
    try:
        return json.loads(cleaned)
    except Exception:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(cleaned[start:end + 1])
            except Exception:
                return {}
    return {}


def _normalize_openai_base_url(base_url):
    text = _safe(base_url)
    if not text:
        return ""
    return re.sub(r"/chat/completions/?$", "", text.rstrip("/"))


def _extract_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join([_extract_text(v) for v in content])
    if isinstance(content, dict):
        return _safe(content.get("text", content.get("content", "")))
    return _safe(content)


def _strip_think(text):
    lower = text.lower()
    idx = lower.rfind("</think>")
    if idx >= 0:
        return text[idx + len("</think>"):]
    return text


def _strip_code_fence(text):
    txt = _safe(text)
    if txt.startswith("```") and txt.endswith("```"):
        line_idx = txt.find("\n")
        if line_idx >= 0:
            txt = txt[line_idx + 1:-3]
    return txt.strip()


def _safe(value):
    if value is None:
        return ""
    return str(value).strip()


class _RuntimeConfigMixin(object):
    CONFIG_FILE_NAME = "config.json"
    ENV_CONFIG_PATH_KEY = "METADATA_CONFIG_FILE"

    def _params(self, kvargs):
        params = self._load_runtime_config()
        params.update(self._decode_kvargs(kvargs))
        return params

    def _load_runtime_config(self):
        path = os.environ.get(self.ENV_CONFIG_PATH_KEY)
        if not path:
            path = os.path.join(os.path.dirname(os.path.abspath(__file__)), self.CONFIG_FILE_NAME)
        if not os.path.exists(path):
            return {}
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        llm = data.get("llm", {}) if isinstance(data.get("llm", {}), dict) else {}
        neo4j = data.get("neo4j", {}) if isinstance(data.get("neo4j", {}), dict) else {}
        return {
            "llmEnabled": self._bool_to_str(self._first(data.get("llmEnabled"), llm.get("enabled"), True)),
            "llmBaseUrl": self._first(data.get("llmBaseUrl"), llm.get("baseUrl"), ""),
            "llmApiKey": self._first(data.get("llmApiKey"), llm.get("apiKey"), ""),
            "llmModel": self._first(data.get("llmModel"), llm.get("model"), ""),
            "llmVisionModel": self._first(data.get("llmVisionModel"), llm.get("visionModel"), llm.get("model"), ""),
            "neo4jEnabled": self._bool_to_str(self._first(data.get("neo4jEnabled"), neo4j.get("enabled"), True)),
            "neo4jUri": self._first(data.get("neo4jUri"), neo4j.get("uri"), ""),
            "neo4jUsername": self._first(data.get("neo4jUsername"), neo4j.get("username"), ""),
            "neo4jPassword": self._first(data.get("neo4jPassword"), neo4j.get("password"), ""),
        }

    def _decode_kvargs(self, kvargs):
        out = {}
        if not kvargs:
            return out
        for key, value in kvargs.items():
            k = key.decode("utf-8", errors="ignore") if isinstance(key, bytes) else str(key)
            out[k] = self._decode(value)
        return out

    def _decode(self, value):
        if value is None:
            return ""
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="ignore")
        return str(value)

    def _first(self, *values):
        for value in values:
            if value is None:
                continue
            if isinstance(value, bool):
                return value
            text = str(value).strip()
            if text:
                return text
        return ""

    def _bool_to_str(self, value):
        if isinstance(value, bool):
            return "true" if value else "false"
        return "true" if str(value).strip().lower() in ("1", "true", "yes", "y", "on") else "false"

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()

    def _result(self, status, keywords=None, fields=None, field_kind="field", message=""):
        return [
            ["status", "keywords", "fields", "fieldKind", "message"],
            ["BINARY", "BINARY", "BINARY", "BINARY", "BINARY"],
            [
                status,
                json.dumps(keywords or [], ensure_ascii=False),
                json.dumps(fields or [], ensure_ascii=False),
                field_kind or "field",
                message or "",
            ],
        ]

    def _log_data_summary(self, udf_name, data, kvargs):
        try:
            print(
                "SemanticKeywordExtract data summary:",
                json.dumps(
                    {
                        "udf": udf_name,
                        "dataType": type(data).__name__,
                        "rows": len(data) if isinstance(data, list) else None,
                        "rowColumnCounts": self._row_column_counts(data, 8),
                        "header": self._preview_row(data, 0, 12, 80),
                        "typeRow": self._preview_row(data, 1, 12, 80),
                        "firstDataRow": self._preview_row(data, 2, 12, 120),
                        "kvargs": self._preview_kvargs(kvargs),
                    },
                    ensure_ascii=False,
                ),
            )
        except Exception as exc:
            print("SemanticKeywordExtract data summary failed:", str(exc))

    def _row_column_counts(self, data, max_rows):
        if not isinstance(data, list):
            return []
        out = []
        for row in data[:max_rows]:
            out.append(len(row) if isinstance(row, list) else None)
        return out

    def _preview_row(self, data, row_idx, max_cols, max_len):
        if not isinstance(data, list) or row_idx >= len(data) or not isinstance(data[row_idx], list):
            return []
        return [self._preview_value(v, max_len) for v in data[row_idx][:max_cols]]

    def _preview_kvargs(self, kvargs):
        out = {}
        if not kvargs:
            return out
        for key, value in kvargs.items():
            k = key.decode("utf-8", errors="ignore") if isinstance(key, bytes) else str(key)
            out[k] = self._preview_value(value, 120)
        return out

    def _preview_value(self, value, max_len):
        if value is None:
            return None
        if isinstance(value, bytes):
            text = value.decode("utf-8", errors="ignore")
            prefix = "bytes:"
        else:
            text = str(value)
            prefix = ""
        text = text.replace("\r", "\\r").replace("\n", "\\n")
        if len(text) > max_len:
            text = text[:max_len] + "...(len=" + str(len(text)) + ")"
        return prefix + text


class _LeafSemanticKeywordUDF(_RuntimeConfigMixin):
    DATA_TYPE = ""
    EXTRACTOR = RelationalMetadataExtractor
    FIELD_KIND = "field"

    def transform(self, data, args, kvargs):
        self._log_data_summary(self.__class__.__name__, data, kvargs)
        try:
            params = self._params(kvargs)
            data_type = self.DATA_TYPE or self._safe(params.get("dataType", "")).lower()
            params["dataType"] = data_type
            extractor = self.EXTRACTOR(data, args, params)
            extracted = extractor.extract()

            fields = dedup_strings(extracted.get("fields", []) or [], 120)
            entities = dedup_strings(extracted.get("entities", []) or [], 120)
            raw_keywords = dedup_strings(extracted.get("keywords", []) or [], 80)
            field_kind = self._safe(extracted.get("fieldKind", self.FIELD_KIND)) or self.FIELD_KIND
            fallback = raw_keywords or fields or entities
            skip_keyword_normalization = bool(extracted.get("skipKeywordNormalization", False))

            asset = {
                "logicalPath": self._safe(params.get("logicalPath", "")),
                "fileName": self._safe(params.get("fileName", "")),
                "dataType": data_type,
                "fileFormat": self._safe(params.get("fileFormat", "")),
                "fieldKind": field_kind,
                "fields": fields,
                "entities": entities,
                "keywords": raw_keywords,
            }
            keywords = []
            if not skip_keyword_normalization:
                keywords = normalize_asset_keywords(params, asset, fallback=fallback, max_count=12)

            writer = Neo4jGraphWriter(params)
            persist_message = writer.persist(
                logical_path=params.get("logicalPath", ""),
                data_type=data_type,
                file_name=params.get("fileName", ""),
                file_format=params.get("fileFormat", ""),
                file_size=params.get("fileSize", "0"),
                create_time=params.get("createTime", ""),
                fields=fields,
                field_kind=field_kind,
                entities=entities,
                triples=extracted.get("triples", []) or [],
                keywords=keywords,
            )
            message = self._safe(extracted.get("message", ""))
            if persist_message:
                message = (message + "; " + persist_message) if message else persist_message
            return self._result("SUCCESS", keywords, fields, field_kind, message)
        except Exception as exc:
            return self._result("FAILED", [], [], self.FIELD_KIND, str(exc))


class RelationalSemanticKeywordExtract(_LeafSemanticKeywordUDF):
    DATA_TYPE = "relational"
    EXTRACTOR = RelationalMetadataExtractor
    FIELD_KIND = "column"


class TimeSeriesSemanticKeywordExtract(_LeafSemanticKeywordUDF):
    DATA_TYPE = "timeseries"
    EXTRACTOR = TimeSeriesMetadataExtractor
    FIELD_KIND = "column"


class KeyValueSemanticKeywordExtract(_LeafSemanticKeywordUDF):
    DATA_TYPE = "keyvalue"
    EXTRACTOR = KeyValueMetadataExtractor
    FIELD_KIND = "key"


class DocumentSemanticKeywordExtract(_LeafSemanticKeywordUDF):
    DATA_TYPE = "document"
    EXTRACTOR = DocumentMetadataExtractor
    FIELD_KIND = "field"


class FileSemanticKeywordExtract(_LeafSemanticKeywordUDF):
    DATA_TYPE = "file"
    EXTRACTOR = FileMetadataExtractor
    FIELD_KIND = "field"


class DirectorySemanticKeywordExtract(_RuntimeConfigMixin):
    def transform(self, data, args, kvargs):
        self._log_data_summary(self.__class__.__name__, data, kvargs)
        try:
            params = self._params(kvargs)
            target_path = self._asset_path({
                "logicalPath": params.get("logicalPath", ""),
                "fileName": params.get("fileName", ""),
                "dataType": "directory",
            })
            child_keywords = self._read_immediate_child_keywords(data, target_path)
            if not child_keywords:
                return self._result("FAILED", [], [], "field", "directory has no completed child keywords")

            keywords = summarize_directory_keywords(params, target_path, child_keywords, 12)
            writer = Neo4jGraphWriter(params)
            message = writer.persist(
                logical_path=target_path,
                data_type="directory",
                file_name=self._leaf_name(target_path),
                file_format="",
                file_size="0",
                create_time=params.get("createTime", ""),
                fields=[],
                field_kind="field",
                entities=[],
                triples=[],
                keywords=keywords,
            )
            return self._result("SUCCESS", keywords, [], "field", message)
        except Exception as exc:
            return self._result("FAILED", [], [], "field", str(exc))

    def _read_immediate_child_keywords(self, data, target_path):
        rows = self._rows(data)
        out = []
        for row in rows:
            if self._safe(row.get("isValid", "true")).lower() == "false":
                continue
            status = self._safe(row.get("knowledgeExtractStatus", "")).upper()
            if status != "SUCCESS":
                continue
            child_path = self._asset_path(row)
            if self._parent_path(child_path) != target_path:
                continue
            kws = self._parse_keywords(row.get("semanticKeywords", ""))
            if kws:
                out.extend(kws)
        return dedup_strings(out, 80)

    def _rows(self, data):
        if not isinstance(data, list) or not data:
            return []
        headers = []
        start = 0
        if isinstance(data[0], list):
            candidate = [self._column_name(v) for v in data[0]]
            if any(v in ("logicalPath", "fileName", "dataType", "semanticKeywords") for v in candidate):
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

    def _asset_path(self, row):
        logical_path = self._normalize_path(row.get("logicalPath", ""))
        data_type = self._safe(row.get("dataType", "")).lower()
        file_name = self._safe(row.get("fileName", ""))
        if not file_name:
            return logical_path
        if logical_path.endswith("/" + file_name):
            return logical_path
        return self._normalize_path(logical_path + "/" + file_name)

    def _parse_keywords(self, text):
        raw = self._safe(text)
        if not raw:
            return []
        try:
            node = json.loads(raw)
            if isinstance(node, list):
                return dedup_strings([v for v in node if isinstance(v, str)], 80)
        except Exception:
            pass
        return dedup_strings([v.strip() for v in raw.split(",")], 80)

    def _normalize_path(self, value):
        path = self._safe(value)
        if not path:
            return "/"
        if not path.startswith("/"):
            path = "/" + path
        while len(path) > 1 and path.endswith("/"):
            path = path[:-1]
        return path

    def _parent_path(self, value):
        path = self._normalize_path(value)
        if path == "/":
            return ""
        idx = path.rfind("/")
        if idx <= 0:
            return "/"
        return path[:idx]

    def _leaf_name(self, value):
        path = self._normalize_path(value)
        if path == "/":
            return "/"
        return path[path.rfind("/") + 1:]
