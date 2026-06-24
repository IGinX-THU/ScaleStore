import json
import os
import re

from metadata.neo4j_writer import Neo4jGraphWriter


class SchemaKnowledgeExtract:
    CONFIG_FILE_NAME = "config.json"
    ENV_CONFIG_PATH_KEY = "METADATA_CONFIG_FILE"

    def __init__(self):
        self.runtime_config = self._load_runtime_config()

    def transform(self, data, args, kvargs):
        print("Execute UDF SchemaKnowledgeExtract")
        try:
            params = self._build_params(self._decode_kvargs(kvargs))
            rows = self._extract_schema_rows(data)
            assets = self._group_assets(rows, params)

            if not assets:
                return self._build_result("SUCCESS", 0, 0, 0, "no filesystem schema assets found")

            semantic = self._extract_semantics(params, rows, assets)
            writer = Neo4jGraphWriter(params)
            entity_count = 0
            relation_count = 0

            for asset in assets:
                asset_semantic = self._semantic_for_asset(semantic, asset, len(assets))
                entities = self._dedup_strings(asset.get("entities", []) + asset_semantic.get("entities", []), 120)
                triples = []
                entity_count += len(entities)
                relation_count += len(triples)

                writer.persist(
                    logical_path=asset.get("logicalPath", ""),
                    data_type=asset.get("dataType", "document"),
                    file_name=asset.get("fileName", ""),
                    file_format=asset.get("fileFormat", ""),
                    file_size="0",
                    create_time="",
                    fields=asset.get("fields", []),
                    field_kind=asset.get("fieldKind", "field"),
                    entities=entities,
                    triples=triples,
                )

            return self._build_result(
                "SUCCESS",
                len(assets),
                entity_count,
                relation_count,
                "schema knowledge graph persisted",
            )
        except Exception as exc:
            return self._build_result("FAILED", 0, 0, 0, str(exc))

    def _build_params(self, kv):
        cfg = self.runtime_config or {}
        return {
            "description": self._safe(kv.get("description", "")),
            "descriptionDocument": self._safe(kv.get("descriptionDocument", "description.txt")) or "description.txt",
            "logicalPath": self._safe(kv.get("logicalPath", "/extern/filesystem")) or "/extern/filesystem",
            "schemaPrefix": self._safe(kv.get("schemaPrefix", "")),
            "sourceType": self._safe(kv.get("sourceType", "filesystem")) or "filesystem",

            "llmEnabled": self._bool_to_str(cfg.get("llmEnabled", True)),
            "llmBaseUrl": self._safe(cfg.get("llmBaseUrl", "")),
            "llmApiKey": self._safe(cfg.get("llmApiKey", "")),
            "llmModel": self._safe(cfg.get("llmModel", "")),
            "llmVisionModel": self._safe(cfg.get("llmVisionModel", "")),

            "neo4jEnabled": self._bool_to_str(cfg.get("neo4jEnabled", True)),
            "neo4jUri": self._safe(cfg.get("neo4jUri", "")),
            "neo4jUsername": self._safe(cfg.get("neo4jUsername", "")),
            "neo4jPassword": self._safe(cfg.get("neo4jPassword", "")),
        }

    def _extract_schema_rows(self, data):
        rows = []
        if not isinstance(data, list) or not data:
            return rows

        headers = []
        start = 0
        if isinstance(data[0], list):
            candidate = [self._decode(v) for v in data[0]]
            lower = [v.lower() for v in candidate]
            if "path" in lower or "type" in lower:
                headers = candidate
                start = 1

        path_idx = 0
        type_idx = 1
        for idx, header in enumerate(headers):
            lower = header.lower()
            if lower == "path" or lower.endswith("path") or ".path" in lower:
                path_idx = idx
            elif lower == "type" or lower.endswith("type") or ".type" in lower:
                type_idx = idx

        for row in data[start:]:
            if not isinstance(row, list) or len(row) <= path_idx:
                continue
            path = self._decode(row[path_idx])
            typ = self._decode(row[type_idx]) if len(row) > type_idx else ""
            if path and "." in path:
                rows.append({"path": path, "type": typ})
        return rows

    def _group_assets(self, rows, params):
        grouped = {}
        schema_prefix = self._safe(params.get("schemaPrefix", ""))
        root_logical = self._normalize_path(params.get("logicalPath", "/extern/filesystem"))
        prefix_size = len(self._split_unescaped(schema_prefix))
        description_leaf = self._description_leaf(params).lower()

        for row in rows:
            segments = self._split_unescaped(row.get("path", ""))
            if not segments:
                continue

            file_idx = self._find_file_segment_index(segments, prefix_size)
            if file_idx < 0:
                continue

            file_name = segments[file_idx]
            if file_name.lower() == description_leaf:
                continue

            asset_key = ".".join([self._escape_path_segment(v) for v in segments[:file_idx + 1]])
            asset = grouped.get(asset_key)
            if asset is None:
                file_format = self._extension(file_name)
                logical_path = self._asset_logical_path(root_logical, segments, prefix_size, file_idx)
                asset = {
                    "assetPath": asset_key,
                    "logicalPath": logical_path,
                    "fileName": file_name,
                    "fileFormat": file_format,
                    "dataType": self._infer_data_type(file_name, file_format, []),
                    "fields": [],
                    "fieldKind": "field",
                    "entities": [],
                    "triples": [],
                }
                grouped[asset_key] = asset

            if len(segments) > file_idx + 1:
                field = segments[-1]
                if field and field not in asset["fields"]:
                    asset["fields"].append(field)

            asset["dataType"] = self._infer_data_type(file_name, asset.get("fileFormat", ""), asset.get("fields", []))
            if asset["dataType"] in ("relational", "timeseries"):
                asset["fieldKind"] = "column"
            elif asset["dataType"] == "keyvalue":
                asset["fieldKind"] = "key"
            else:
                asset["fieldKind"] = "field"

        return list(grouped.values())

    def _extract_semantics(self, params, rows, assets):
        if str(params.get("llmEnabled", "true")).lower() != "true":
            return {"assets": [], "entities": [], "triples": []}

        model = self._safe(params.get("llmModel", ""))
        api_key = self._safe(params.get("llmApiKey", ""))
        if not model or not api_key:
            return {"assets": [], "entities": [], "triples": []}

        schema_summary = []
        for asset in assets[:80]:
            schema_summary.append({
                "logicalPath": asset.get("logicalPath", ""),
                "fileName": asset.get("fileName", ""),
                "dataType": asset.get("dataType", ""),
                "columns": asset.get("fields", [])[:120],
            })

        prompt = (
            "You are a knowledge graph extraction assistant for heterogeneous industrial data. "
            "Use only the provided description document content and raw `show columns` result. "
            "Do not invent devices, fields, entities, or relations that are not supported by the input. "
            "Return strict JSON only in this shape: "
            "{\"assets\":[{\"logicalPath\":\"/path\",\"fileName\":\"file.csv\",\"entities\":[\"entity name\"]}]}. "
            "Assign each entity only to the asset whose file content or columns support it. "
            "Do not return global entities, triples, or entity-to-entity relations. "
            "Prefer entities such as devices, systems, processes, measurements, business objects, and important files."
        )
        user_content = json.dumps({
            "descriptionDocument": params.get("descriptionDocument", ""),
            "description": params.get("description", ""),
            "schemaPrefix": params.get("schemaPrefix", ""),
            "showColumns": rows[:500],
            "assets": schema_summary,
        }, ensure_ascii=False)

        raw = self._llm_chat(params, model, [
            {"role": "system", "content": prompt},
            {"role": "user", "content": user_content},
        ])
        return self._parse_llm_json_payload(raw)

    def _llm_chat(self, params, model, messages):
        try:
            from openai import OpenAI
        except Exception as exc:
            raise RuntimeError("openai package is required: pip install openai") from exc

        api_key = self._safe(params.get("llmApiKey", ""))
        base_url = self._normalize_openai_base_url(self._safe(params.get("llmBaseUrl", "")))
        client = OpenAI(api_key=api_key, base_url=base_url) if base_url else OpenAI(api_key=api_key)
        response = client.chat.completions.create(model=model, temperature=0.1, messages=messages)
        if response and response.choices and response.choices[0].message:
            return self._extract_text(response.choices[0].message.content)
        return ""

    def _parse_llm_json_payload(self, text):
        cleaned = self._strip_code_fence(self._strip_think(text or ""))
        try:
            node = json.loads(cleaned)
        except Exception:
            start = cleaned.find("{")
            end = cleaned.rfind("}")
            if start < 0 or end <= start:
                return {"entities": [], "triples": []}
            node = json.loads(cleaned[start:end + 1])

        asset_semantics = []
        source_assets = node.get("assets", [])
        if isinstance(source_assets, list):
            for item in source_assets:
                if not isinstance(item, dict):
                    continue
                logical_path = self._normalize_path(item.get("logicalPath", ""))
                file_name = self._safe(item.get("fileName", ""))
                entities = []
                for entity in item.get("entities", []):
                    if isinstance(entity, str) and entity.strip():
                        entities.append(entity.strip())
                triples = []
                source = item.get("triples", item.get("relations", []))
                if isinstance(source, list):
                    for rel in source:
                        if not isinstance(rel, dict):
                            continue
                        subject = self._safe(rel.get("subject", ""))
                        obj = self._safe(rel.get("object", ""))
                        if subject:
                            entities.append(subject)
                        if obj:
                            entities.append(obj)
                if logical_path or file_name or entities:
                    asset_semantics.append({
                        "logicalPath": logical_path,
                        "fileName": file_name,
                        "entities": self._dedup_strings(entities, 120),
                        "triples": triples,
                    })

        entities = []
        for entity in node.get("entities", []):
            if isinstance(entity, str) and entity.strip():
                entities.append(entity.strip())

        triples = []
        source = node.get("triples", node.get("relations", []))
        if isinstance(source, list):
            for item in source:
                if not isinstance(item, dict):
                    continue
                subject = self._safe(item.get("subject", ""))
                predicate = self._safe(item.get("predicate", item.get("relation", "")))
                obj = self._safe(item.get("object", ""))
                if subject and predicate and obj:
                    triples.append({"subject": subject, "predicate": predicate, "object": obj})

        return {
            "assets": asset_semantics,
            "entities": self._dedup_strings(entities, 120),
            "triples": self._dedup_triples(triples, 180),
        }

    def _semantic_for_asset(self, semantic, asset, asset_count):
        if not isinstance(semantic, dict):
            return {"entities": [], "triples": []}

        asset_key = self._asset_identity(asset)
        matches = []
        for item in semantic.get("assets", []) or []:
            if not isinstance(item, dict):
                continue
            if self._asset_identity(item) == asset_key:
                matches.append(item)

        entities = []
        triples = []
        for item in matches:
            entities.extend(item.get("entities", []) or [])
            triples.extend(item.get("triples", []) or [])

        # Backward compatibility for older LLM responses. With multiple assets,
        # global entities are ambiguous, so do not attach them to every asset.
        if asset_count == 1:
            entities.extend(semantic.get("entities", []) or [])
            triples.extend(semantic.get("triples", []) or [])

        return {
            "entities": self._dedup_strings(entities, 120),
            "triples": self._dedup_triples(triples, 180),
        }

    def _asset_identity(self, value):
        if not isinstance(value, dict):
            return "|"
        return self._normalize_path(value.get("logicalPath", "")) + "|" + self._safe(value.get("fileName", "")).lower()

    def _asset_logical_path(self, root_logical, segments, prefix_size, file_idx):
        path = self._normalize_path(root_logical)
        for idx in range(prefix_size, file_idx):
            segment = self._safe(segments[idx])
            if segment:
                path += "/" + segment
        return self._normalize_path(path)

    def _infer_data_type(self, file_name, file_format, fields):
        ext = self._safe(file_format).lower()
        lower_name = self._safe(file_name).lower()
        if ext in ("jpg", "jpeg", "png", "bmp", "gif", "webp"):
            return "image"
        if ext in ("json", "yaml", "yml"):
            return "keyvalue"
        if ext in ("csv", "tsv", "txt"):
            if "timeseries" in lower_name or "time_series" in lower_name or "telemetry" in lower_name:
                return "timeseries"
            for field in fields or []:
                lower = self._safe(field).lower()
                if lower in ("time", "timestamp", "date", "datetime") or lower.endswith("_time") or lower.endswith("_timestamp"):
                    return "timeseries"
            return "relational"
        return "document"

    def _find_file_segment_index(self, segments, start):
        for idx in range(max(0, start), len(segments)):
            if self._extension(segments[idx]):
                return idx
        return -1

    def _description_leaf(self, params):
        text = self._safe(params.get("descriptionDocument", "description.txt")).replace("\\", "/")
        if not text:
            return "description.txt"
        idx = text.rfind("/")
        if idx >= 0 and idx < len(text) - 1:
            return text[idx + 1:]
        return text

    def _split_unescaped(self, text):
        out = []
        cur = []
        escaping = False
        for ch in self._safe(text):
            if escaping:
                cur.append(ch)
                escaping = False
                continue
            if ch == "\\":
                escaping = True
                continue
            if ch == ".":
                out.append("".join(cur))
                cur = []
                continue
            cur.append(ch)
        if escaping:
            cur.append("\\")
        out.append("".join(cur))
        return out

    def _extension(self, name):
        text = self._safe(name)
        idx = text.rfind(".")
        if idx < 0 or idx == len(text) - 1:
            return ""
        return text[idx + 1:].lower()

    def _build_result(self, status, asset_count, entity_count, relation_count, message):
        return [
            ["status", "assetCount", "entityCount", "relationCount", "message"],
            ["BINARY", "LONG", "LONG", "LONG", "BINARY"],
            [status, int(asset_count), int(entity_count), int(relation_count), message],
        ]

    def _load_runtime_config(self):
        config_path = self._resolve_config_path()
        if not config_path:
            return {}
        with open(config_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            return {}
        return self._normalize_runtime_config(data)

    def _normalize_runtime_config(self, data):
        llm_cfg = data.get("llm", {}) if isinstance(data.get("llm", {}), dict) else {}
        neo4j_cfg = data.get("neo4j", {}) if isinstance(data.get("neo4j", {}), dict) else {}

        llm_model = self._first_non_empty(data.get("llmModel", ""), llm_cfg.get("model", ""))
        llm_vision_model = self._first_non_empty(
            data.get("llmVisionModel", ""),
            llm_cfg.get("visionModel", ""),
            llm_model,
        )

        return {
            "llmEnabled": self._to_bool(
                self._first_non_empty(data.get("llmEnabled", None), llm_cfg.get("enabled", None)),
                True,
            ),
            "llmBaseUrl": self._first_non_empty(data.get("llmBaseUrl", ""), llm_cfg.get("baseUrl", "")),
            "llmApiKey": self._first_non_empty(data.get("llmApiKey", ""), llm_cfg.get("apiKey", "")),
            "llmModel": llm_model,
            "llmVisionModel": llm_vision_model,
            "neo4jEnabled": self._to_bool(
                self._first_non_empty(data.get("neo4jEnabled", None), neo4j_cfg.get("enabled", None)),
                True,
            ),
            "neo4jUri": self._first_non_empty(data.get("neo4jUri", ""), neo4j_cfg.get("uri", "")),
            "neo4jUsername": self._first_non_empty(data.get("neo4jUsername", ""), neo4j_cfg.get("username", "")),
            "neo4jPassword": self._first_non_empty(data.get("neo4jPassword", ""), neo4j_cfg.get("password", "")),
        }

    def _resolve_config_path(self):
        env_path = os.environ.get(self.ENV_CONFIG_PATH_KEY)
        if env_path and os.path.exists(env_path):
            return env_path
        here = os.path.dirname(os.path.abspath(__file__))
        direct = os.path.join(here, self.CONFIG_FILE_NAME)
        if os.path.exists(direct):
            return direct
        return ""

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

    def _escape_path_segment(self, segment):
        return self._safe(segment).replace("\\", "\\\\").replace(".", "\\.")

    def _normalize_path(self, path):
        text = self._safe(path)
        if not text:
            return "/"
        if not text.startswith("/"):
            text = "/" + text
        while len(text) > 1 and text.endswith("/"):
            text = text[:-1]
        return text

    def _normalize_openai_base_url(self, base_url):
        text = self._safe(base_url)
        if not text:
            return ""
        return re.sub(r"/chat/completions/?$", "", text.rstrip("/"))

    def _extract_text(self, content):
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "\n".join([self._extract_text(v) for v in content])
        if isinstance(content, dict):
            return self._safe(content.get("text", content.get("content", "")))
        return self._safe(content)

    def _strip_think(self, text):
        lower = text.lower()
        idx = lower.rfind("</think>")
        if idx >= 0:
            return text[idx + len("</think>"):]
        return text

    def _strip_code_fence(self, text):
        txt = self._safe(text)
        if txt.startswith("```") and txt.endswith("```"):
            line_idx = txt.find("\n")
            if line_idx >= 0:
                txt = txt[line_idx + 1:-3]
        return txt.strip()

    def _bool_to_str(self, value):
        return "true" if self._to_bool(value, False) else "false"

    def _to_bool(self, value, default_value):
        if isinstance(value, bool):
            return value
        if value is None:
            return default_value
        text = self._safe(value).lower()
        if text in ("1", "true", "yes", "y", "on"):
            return True
        if text in ("0", "false", "no", "n", "off"):
            return False
        return default_value

    def _first_non_empty(self, *values):
        for value in values:
            if value is None:
                continue
            if isinstance(value, bool):
                return value
            text = self._safe(value)
            if text:
                return text
        return ""

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()
