from .base_extractor import BaseMetadataExtractor


class TimeSeriesMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = self.extract_fields()
        trimmed = []
        for field in fields:
            normalized = self.normalize_iginx_duplicate_key_name(field)
            lower = normalized.lower()
            if lower in ("time", "timestamp"):
                continue
            trimmed.append(normalized)
        if trimmed:
            fields = self.dedup_strings(trimmed, 120)

        return {
            "fieldKind": "column",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "timeseries field extraction by udf",
        }
