import json
from metadata.extractors.document_extractor import DocumentMetadataExtractor
from metadata.extractors.image_extractor import ImageMetadataExtractor
from metadata.extractors.keyvalue_extractor import KeyValueMetadataExtractor
from metadata.extractors.relational_extractor import RelationalMetadataExtractor
from metadata.extractors.timeseries_extractor import TimeSeriesMetadataExtractor
from metadata.neo4j_writer import Neo4jGraphWriter


class UDFMetadataExtract:
    def __init__(self):
        pass

    def transform(self, data, args, kvargs):
        try:
            params = self._decode_kvargs(kvargs)
            data_type = self._safe(params.get("dataType", "")).lower()

            extractor = self._create_extractor(data_type, data, args, params)
            extracted = extractor.extract()

            fields = self._dedup_strings(extracted.get("fields", []), 120)
            entities = self._dedup_strings(extracted.get("entities", []), 120)
            triples = self._dedup_triples(extracted.get("triples", []), 180)

            field_kind = self._safe(extracted.get("fieldKind", "field")) or "field"
            if not fields and field_kind in ("column", "key"):
                field_kind = "field"

            if data_type in ("relational", "timeseries", "keyvalue"):
                entities = []
                triples = []
                if data_type == "keyvalue":
                    if field_kind not in ("key", "column"):
                        field_kind = "key"
                else:
                    if field_kind not in ("column", "key"):
                        field_kind = "column"
            elif data_type in ("document", "image"):
                fields = []
                if field_kind in ("column", "key"):
                    field_kind = "field"

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
                triples=triples,
            )

            message = self._safe(extracted.get("message", ""))
            if persist_message:
                message = (message + "; " + persist_message) if message else persist_message

            payload = {
                "status": "SUCCESS",
                "fields": fields,
                "fieldKind": field_kind,
                "entities": entities,
                "triples": triples,
                "message": message,
            }
            return self._build_result(payload)
        except Exception as exc:
            payload = {
                "status": "FAILED",
                "fields": [],
                "fieldKind": "field",
                "entities": [],
                "triples": [],
                "message": str(exc),
            }
            return self._build_result(payload)

    def _create_extractor(self, data_type, data, args, params):
        mapping = {
            "relational": RelationalMetadataExtractor,
            "timeseries": TimeSeriesMetadataExtractor,
            "keyvalue": KeyValueMetadataExtractor,
            "document": DocumentMetadataExtractor,
            "image": ImageMetadataExtractor,
        }
        extractor_cls = mapping.get(data_type, RelationalMetadataExtractor)
        return extractor_cls(data, args, params)

    def _build_result(self, payload):
        return [
            ["(status)", "(fields)", "(fieldKind)", "(entities)", "(triples)", "(message)"],
            ["BINARY", "BINARY", "BINARY", "BINARY", "BINARY", "BINARY"],
            [
                payload.get("status", "SUCCESS"),
                json.dumps(payload.get("fields", []), ensure_ascii=False),
                payload.get("fieldKind", "field"),
                json.dumps(payload.get("entities", []), ensure_ascii=False),
                json.dumps(payload.get("triples", []), ensure_ascii=False),
                payload.get("message", ""),
            ],
        ]

    def _decode_kvargs(self, kvargs):
        out = {}
        if not kvargs:
            return out
        for key, value in kvargs.items():
            k = key.decode("utf-8", errors="ignore") if isinstance(key, bytes) else str(key)
            if isinstance(value, bytes):
                out[k] = value.decode("utf-8", errors="ignore")
            else:
                out[k] = str(value)
        return out

    def _dedup_strings(self, values, max_count):
        seen = set()
        out = []
        for value in values or []:
            text = str(value).strip()
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
        out = []
        for triple in triples or []:
            if not isinstance(triple, dict):
                continue
            subject = str(triple.get("subject", "")).strip()
            predicate = str(triple.get("predicate", triple.get("relation", ""))).strip()
            obj = str(triple.get("object", "")).strip()
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
