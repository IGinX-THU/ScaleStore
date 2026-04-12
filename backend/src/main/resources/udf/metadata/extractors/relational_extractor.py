from .base_extractor import BaseMetadataExtractor


class RelationalMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = []
        for field in self.extract_fields():
            lower = str(field).lower().strip()
            # UDF query result may include synthetic key column; legacy behavior excluded it.
            if lower == "key":
                continue
            fields.append(field)

        return {
            "fieldKind": "column",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "relational field extraction by udf",
        }
