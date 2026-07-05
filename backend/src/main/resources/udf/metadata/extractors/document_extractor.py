from .base_extractor import BaseMetadataExtractor


class DocumentMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = []
        for field in self.extract_fields():
            if str(field).lower().strip() == "key":
                continue
            fields.append(field)

        return {
            "fieldKind": "field",
            "fields": fields,
            "keywords": fields,
            "entities": [],
            "triples": [],
            "message": "document field extraction by udf",
        }
