from .base_extractor import BaseMetadataExtractor


class DocumentMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        # 当前文档类型不读取全文；将可见字段作为初始关键词，后续统一由关键词归一化处理。
        fields = []
        for field in self.extract_fields():
            fields.append(self.normalize_iginx_duplicate_key_name(field))
        fields = self.dedup_strings(fields, 120)

        return self.build_result(
            "field",
            fields=fields,
            keywords=fields,
            message="document field extraction by udf",
        )
