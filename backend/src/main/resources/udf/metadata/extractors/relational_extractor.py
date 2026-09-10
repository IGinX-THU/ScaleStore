from .base_extractor import BaseMetadataExtractor


class RelationalMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        # 关系型内容的可靠语义输入是列名；不解析行值以避免把样本数据误当成领域实体。
        fields = []
        for field in self.extract_fields():
            fields.append(self.normalize_iginx_duplicate_key_name(field))
        fields = self.dedup_strings(fields, 120)

        return self.build_result(
            "column",
            fields=fields,
            message="relational field extraction by udf",
        )
