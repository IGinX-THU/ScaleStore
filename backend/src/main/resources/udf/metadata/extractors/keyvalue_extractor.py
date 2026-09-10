from .base_extractor import BaseMetadataExtractor


class KeyValueMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        # 键值数据优先从 JSON 键层级提取；没有可解析内容时才退回到查询表头。
        text_content = self.extract_text_content()
        fields = self.extract_keyvalue_keys(text_content)
        if not fields:
            fields = self.extract_fields()
        fields = self.dedup_strings([self.normalize_iginx_duplicate_key_name(field) for field in fields], 120)

        return self.build_result(
            "key",
            fields=fields,
            message="keyvalue field extraction by udf",
        )
