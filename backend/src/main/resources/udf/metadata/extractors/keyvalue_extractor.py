from .base_extractor import BaseMetadataExtractor


class KeyValueMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        text_content = self.extract_text_content()
        fields = self.extract_keyvalue_keys(text_content)
        if not fields:
            fields = self.extract_fields()
        fields = self.dedup_strings([self.normalize_iginx_duplicate_key_name(field) for field in fields], 120)

        return {
            "fieldKind": "key",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "keyvalue field extraction by udf",
        }
