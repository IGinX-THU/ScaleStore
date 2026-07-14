from .base_extractor import BaseMetadataExtractor


class DocumentMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = []
        for field in self.extract_fields():
            fields.append(self.normalize_iginx_duplicate_key_name(field))
        fields = self.dedup_strings(fields, 120)

        return {
            "fieldKind": "field",
            "fields": fields,
            "keywords": fields,
            "entities": [],
            "triples": [],
            "message": "document field extraction by udf",
        }
