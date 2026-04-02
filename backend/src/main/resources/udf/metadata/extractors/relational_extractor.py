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

        root = self.get_root_entity()

        entities = [root]
        triples = []
        for field in fields[:80]:
            entities.append(field)
            triples.append({"subject": root, "predicate": "has_field", "object": field})

        if not triples:
            triples.append({"subject": root, "predicate": "has_type", "object": "relational"})

        return {
            "fieldKind": "column",
            "fields": fields,
            "entities": self.dedup_strings(entities, 120),
            "triples": self.dedup_triples(triples, 180),
            "message": "relational extraction by udf",
        }
