import json
import os
import re

from metadata.extractors.document_extractor import DocumentMetadataExtractor
from metadata.extractors.image_extractor import ImageMetadataExtractor
from metadata.extractors.keyvalue_extractor import KeyValueMetadataExtractor
from metadata.extractors.relational_extractor import RelationalMetadataExtractor
from metadata.extractors.timeseries_extractor import TimeSeriesMetadataExtractor
from metadata.neo4j_writer import Neo4jGraphWriter


class MetadataExtractExeTransformer:
    CONFIG_FILE_NAME = "config.json"
    ENV_CONFIG_PATH_KEY = "METADATA_CONFIG_FILE"

    STRUCTURED_TYPES = ("relational", "timeseries", "keyvalue")
    SEMANTIC_TYPES = ("document", "image")

    def __init__(self):
        self.runtime_config = self._load_runtime_config()
        self._validate_runtime_config()

    def transform(self, rows):
        print("MetadataExtractExeTransformer received rows")
        if isinstance(rows, list) and rows:
            print(rows[0])

        output = [self._result_header()]
        if not isinstance(rows, list) or len(rows) < 2 or not isinstance(rows[0], list):
            return output

        headers = [self._safe(v) for v in rows[0]]
        for row in rows[1:]:
            output.append(self._process_one(headers, row))
        return output

    def _process_one(self, headers, row):
        record = self._build_record(headers, row)
        params = self._build_params(record)
        meta_key = self._to_int(record.get("metaInfo_key", 0))

        data_type = self._safe(params.get("dataType", "")).lower()
        logical_path = self._safe(params.get("logicalPath", ""))
        file_name = self._safe(params.get("fileName", ""))

        try:
            data_headers, data_values = self._extract_data_columns(headers, row, data_type)
            extracted = self._extract_by_type(data_type, data_headers, data_values, params)

            normalized = self._normalize_extracted(data_type, data_headers, extracted)
            field_kind = self._safe(normalized.get("fieldKind", "field")) or "field"

            fields = self._dedup_strings(normalized.get("fields", []), 120)
            entities = self._dedup_strings(normalized.get("entities", []), 20)
            triples = self._dedup_triples(normalized.get("triples", []), 30)

            writer = Neo4jGraphWriter(params)
            persist_message = writer.persist(
                logical_path=logical_path,
                data_type=data_type,
                file_name=params.get("fileName", ""),
                file_format=params.get("fileFormat", ""),
                file_size=params.get("fileSize", "0"),
                create_time=params.get("createTime", ""),
                fields=fields,
                field_kind=field_kind,
                entities=entities,
                triples=triples,
            )

            message = self._join_message(extracted.get("message", ""), persist_message)
            details_json = json.dumps(
                {
                    "fieldKind": field_kind,
                    "fields": fields,
                    "entities": entities,
                    "triples": triples,
                    "counts": {
                        "fieldCount": len(fields),
                        "entityCount": len(entities),
                        "relationCount": len(triples),
                    },
                },
                ensure_ascii=False,
            )

            return [
                meta_key,
                self._to_binary(logical_path),
                self._to_binary(file_name),
                self._to_binary("SUCCESS"),
                len(entities),
                len(triples),
                self._to_binary(details_json),
                self._to_binary(message),
                self._to_binary(""),
            ]
        except Exception as exc:
            error = self._safe(str(exc))
            details_json = json.dumps(
                {
                    "fieldKind": "",
                    "fields": [],
                    "entities": [],
                    "triples": [],
                    "counts": {
                        "fieldCount": 0,
                        "entityCount": 0,
                        "relationCount": 0,
                    },
                },
                ensure_ascii=False,
            )
            return [
                meta_key,
                self._to_binary(logical_path),
                self._to_binary(file_name),
                self._to_binary("FAILED"),
                0,
                0,
                self._to_binary(details_json),
                self._to_binary(""),
                self._to_binary(error),
            ]

    def _result_header(self):
        return [
            "metaKey",
            "logicalPath",
            "fileName",
            "status",
            "entityCount",
            "relationCount",
            "detailsJson",
            "message",
            "error",
        ]

    def _build_record(self, headers, row):
        values = row if isinstance(row, list) else []
        out = {}
        for idx, header in enumerate(headers):
            out[header] = values[idx] if idx < len(values) else None
        return out

    def _build_params(self, record):
        data_type = self._safe(self._decode(record.get("metaInfo_dataType", ""))).lower()
        return {
            "logicalPath": self._decode(record.get("metaInfo_logicalPath", "")),
            "dataType": data_type,
            "fileName": self._decode(record.get("metaInfo_fileName", "")),
            "fileFormat": self._decode(record.get("metaInfo_fileFormat", "")),
            "fileSize": str(self._to_int(record.get("metaInfo_fileSize", 0))),
            "createTime": self._decode(record.get("metaInfo_createTime", "")),
            "contentPath": self._decode(record.get("metaInfo_contentPath", "")),

            "llmEnabled": self._bool_to_str(self.runtime_config.get("llmEnabled", True)),
            "llmBaseUrl": self._safe(self.runtime_config.get("llmBaseUrl", "")),
            "llmApiKey": self._safe(self.runtime_config.get("llmApiKey", "")),
            "llmModel": self._safe(self.runtime_config.get("llmModel", "")),
            "llmVisionModel": self._safe(self.runtime_config.get("llmVisionModel", "")),

            "neo4jEnabled": self._bool_to_str(self.runtime_config.get("neo4jEnabled", True)),
            "neo4jUri": self._safe(self.runtime_config.get("neo4jUri", "")),
            "neo4jUsername": self._safe(self.runtime_config.get("neo4jUsername", "")),
            "neo4jPassword": self._safe(self.runtime_config.get("neo4jPassword", "")),
        }

    def _extract_data_columns(self, headers, row, data_type):
        indices = []
        for idx, header in enumerate(headers):
            if not header.startswith("data."):
                continue
            if data_type in self.SEMANTIC_TYPES and self._is_data_key_header(header):
                continue
            indices.append(idx)

        values = row if isinstance(row, list) else []
        data_headers = [headers[idx] for idx in indices]
        data_values = [values[idx] if idx < len(values) else None for idx in indices]
        return data_headers, data_values

    def _extract_by_type(self, data_type, data_headers, data_values, params):
        data_matrix = [data_headers, data_values]
        mapping = {
            "relational": RelationalMetadataExtractor,
            "timeseries": TimeSeriesMetadataExtractor,
            "keyvalue": KeyValueMetadataExtractor,
            "document": DocumentMetadataExtractor,
            "image": ImageMetadataExtractor,
        }
        extractor_cls = mapping.get(data_type, RelationalMetadataExtractor)
        extractor = extractor_cls(data_matrix, None, params)
        extracted = extractor.extract() if extractor is not None else {}
        if not isinstance(extracted, dict):
            extracted = {}
        extracted.setdefault("fieldKind", "field")
        extracted.setdefault("fields", [])
        extracted.setdefault("entities", [])
        extracted.setdefault("triples", [])
        extracted.setdefault("message", "metadata extraction completed")
        return extracted

    def _normalize_extracted(self, data_type, data_headers, extracted):
        result = {
            "fieldKind": self._safe(extracted.get("fieldKind", "field")) or "field",
            "fields": extracted.get("fields", []) or [],
            "entities": extracted.get("entities", []) or [],
            "triples": extracted.get("triples", []) or [],
        }

        if data_type in self.STRUCTURED_TYPES:
            header_fields = self._extract_fields_from_headers(
                data_headers,
                include_key=(data_type == "timeseries"),
            )

            if data_type == "keyvalue":
                fields = result.get("fields", [])
                if not fields:
                    fields = header_fields
                fields = [f for f in fields if self._safe(f).lower() != "key"]
                result["fieldKind"] = "key"
                result["fields"] = fields
            else:
                if header_fields:
                    result["fields"] = header_fields

                if data_type == "timeseries":
                    names = [self._safe(v).lower() for v in result.get("fields", [])]
                    if "key" not in names:
                        result["fields"] = ["key"] + result.get("fields", [])
                else:
                    result["fields"] = [
                        f for f in result.get("fields", []) if self._safe(f).lower() != "key"
                    ]

                result["fieldKind"] = "column"

            result["entities"] = []
            result["triples"] = []
            return result

        if data_type in self.SEMANTIC_TYPES:
            result["fieldKind"] = "field"
            result["fields"] = []
            return result

        return result

    def _extract_fields_from_headers(self, data_headers, include_key):
        fields = []
        for header in data_headers:
            if not header.startswith("data."):
                continue

            path = header[len("data."):]
            if not path:
                continue

            leaf = self._extract_leaf(path)
            leaf = self._sanitize_field_name(leaf)
            if not leaf:
                continue

            if leaf.lower() == "key" and not include_key:
                continue
            fields.append(leaf)

        if include_key:
            names = [self._safe(v).lower() for v in fields]
            if "key" not in names:
                fields.insert(0, "key")
        return self._dedup_strings(fields, 120)

    def _extract_leaf(self, token):
        text = self._safe(token)
        if not text:
            return ""

        marker = "__DOT__"
        text = text.replace("\\\\.", marker)
        text = text.replace("\\.", marker)
        parts = text.split(".")
        leaf = parts[-1] if parts else text
        return leaf.replace(marker, ".")

    def _sanitize_field_name(self, value):
        text = self._safe(value)
        if not text:
            return ""
        return re.sub(r"[^A-Za-z0-9_\-\u4e00-\u9fa5]", "", text)

    def _is_data_key_header(self, header):
        return self._safe(header).lower() == "data.key"

    def _join_message(self, first, second):
        left = self._safe(first)
        right = self._safe(second)
        if left and right:
            return left + "; " + right
        return left or right

    def _dedup_strings(self, values, max_count):
        seen = set()
        out = []
        for value in values or []:
            text = self._safe(value)
            if not text:
                continue
            if text in seen:
                continue
            seen.add(text)
            out.append(text)
            if len(out) >= max_count:
                break
        return out

    def _dedup_triples(self, triples, max_count):
        seen = set()
        pair_seen = set()
        out = []
        for triple in triples or []:
            if not isinstance(triple, dict):
                continue

            subject = self._safe(triple.get("subject", ""))
            predicate = self._safe(triple.get("predicate", triple.get("relation", "")))
            obj = self._safe(triple.get("object", ""))
            if not subject or not predicate or not obj:
                continue

            key = self._normalize_display(subject) + "|" + self._normalize_display(predicate) + "|" + self._normalize_display(obj)
            if key in seen:
                continue

            norm_subj = self._normalize_pair(subject)
            norm_obj = self._normalize_pair(obj)
            if not norm_subj or not norm_obj:
                continue

            pair_key = norm_subj + "||" + norm_obj
            if norm_subj > norm_obj:
                pair_key = norm_obj + "||" + norm_subj
            if pair_key in pair_seen:
                continue

            seen.add(key)
            pair_seen.add(pair_key)
            out.append({
                "subject": subject,
                "predicate": predicate,
                "object": obj,
            })
            if len(out) >= max_count:
                break
        return out

    def _normalize_display(self, value):
        return re.sub(r"\s+", " ", self._safe(value))

    def _normalize_pair(self, value):
        return self._safe(value).lower().replace(" ", "")

    def _decode(self, value):
        if value is None:
            return ""
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="ignore").strip()
        return str(value).strip()

    def _to_int(self, value):
        if value is None:
            return 0
        if isinstance(value, bytes):
            try:
                value = value.decode("utf-8", errors="ignore")
            except Exception:
                return 0
        try:
            return int(str(value).strip())
        except Exception:
            return 0

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()

    def _to_binary(self, value):
        if value is None:
            return b""
        if isinstance(value, bytes):
            return value
        return str(value).encode("utf-8")

    def _load_runtime_config(self):
        loaded = self._load_config_file()
        llm_cfg = loaded.get("llm", {}) if isinstance(loaded.get("llm", {}), dict) else {}
        neo4j_cfg = loaded.get("neo4j", {}) if isinstance(loaded.get("neo4j", {}), dict) else {}

        llm_model = self._safe(llm_cfg.get("model", ""))
        llm_vision_model = self._safe(llm_cfg.get("visionModel", ""))
        if not llm_vision_model:
            llm_vision_model = llm_model

        return {
            "llmEnabled": self._to_bool(llm_cfg.get("enabled", True), True),
            "llmBaseUrl": self._safe(llm_cfg.get("baseUrl", "")),
            "llmApiKey": self._safe(llm_cfg.get("apiKey", "")),
            "llmModel": llm_model,
            "llmVisionModel": llm_vision_model,
            "neo4jEnabled": self._to_bool(neo4j_cfg.get("enabled", True), True),
            "neo4jUri": self._safe(neo4j_cfg.get("uri", "")),
            "neo4jUsername": self._safe(neo4j_cfg.get("username", "")),
            "neo4jPassword": self._safe(neo4j_cfg.get("password", "")),
        }

    def _validate_runtime_config(self):
        missing = []

        if self._to_bool(self.runtime_config.get("llmEnabled", True), True):
            for key in ("llmBaseUrl", "llmApiKey", "llmModel"):
                if not self._safe(self.runtime_config.get(key, "")):
                    missing.append("llm." + key)

        if self._to_bool(self.runtime_config.get("neo4jEnabled", True), True):
            for key in ("neo4jUri", "neo4jUsername", "neo4jPassword"):
                if not self._safe(self.runtime_config.get(key, "")):
                    missing.append("neo4j." + key)

        if missing:
            raise RuntimeError(
                "MetadataExtractExeTransformer config missing required fields: "
                + ", ".join(missing)
            )

    def _load_config_file(self):
        config_path = self._resolve_config_path()
        if not config_path:
            raise RuntimeError(
                "MetadataExtractExeTransformer config file not found. "
                "Set env METADATA_CONFIG_FILE or place metadata/config.json next to transformer package."
            )

        try:
            with open(config_path, "r", encoding="utf-8") as fp:
                loaded = json.load(fp)
                if not isinstance(loaded, dict):
                    raise RuntimeError("root JSON object expected")
                return loaded
        except Exception as exc:
            raise RuntimeError(
                "MetadataExtractExeTransformer failed to load config file: "
                + config_path
                + " error="
                + self._safe(str(exc))
            )

    def _resolve_config_path(self):
        env_path = self._safe(os.environ.get(self.ENV_CONFIG_PATH_KEY, ""))
        if env_path:
            if os.path.isfile(env_path):
                return env_path
            raise RuntimeError("MetadataExtractExeTransformer config path from env not found: " + env_path)

        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        default_path = os.path.join(base_dir, self.CONFIG_FILE_NAME)
        if os.path.isfile(default_path):
            return default_path
        return ""

    def _to_bool(self, value, default_value):
        if isinstance(value, bool):
            return value
        if value is None:
            return default_value
        text = self._safe(value).lower()
        if text in ("1", "true", "yes", "on"):
            return True
        if text in ("0", "false", "no", "off"):
            return False
        return default_value

    def _bool_to_str(self, value):
        return "true" if self._to_bool(value, False) else "false"

