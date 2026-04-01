from .base_extractor import BaseMetadataExtractor


class KeyValueMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        text_content = self.extract_text_content()
        fields = self.extract_keyvalue_keys(text_content)
        if not fields:
            fields = self.extract_fields()

        return {
            "fieldKind": "key",
            "fields": fields,
            "entities": [],
            "triples": [],
            "message": "keyvalue field extraction by udf",
        }
