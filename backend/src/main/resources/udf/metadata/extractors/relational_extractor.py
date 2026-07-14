from .base_extractor import BaseMetadataExtractor


class RelationalMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = []
        for field in self.extract_fields():
            fields.append(self.normalize_iginx_duplicate_key_name(field))
        fields = self.dedup_strings(fields, 120)

        return {
            "fieldKind": "column",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "relational field extraction by udf",
        }
