from .base_extractor import BaseMetadataExtractor


class TimeSeriesMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = self.extract_fields()
        trimmed = []
        for field in fields:
            lower = field.lower()
            if lower in ("key", "time", "timestamp"):
                continue
            trimmed.append(field)
        if trimmed:
            fields = trimmed

        return {
            "fieldKind": "column",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "timeseries field extraction by udf",
        }
