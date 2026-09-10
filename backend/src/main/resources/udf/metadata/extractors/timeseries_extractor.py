from .base_extractor import BaseMetadataExtractor


class TimeSeriesMetadataExtractor(BaseMetadataExtractor):
    def extract(self):
        fields = self.extract_fields()
        trimmed = []
        for field in fields:
            normalized = self.normalize_iginx_duplicate_key_name(field)
            lower = normalized.lower()
            # 时间轴是时序存储的技术字段，不应成为业务语义关键词。
            if lower in ("time", "timestamp"):
                continue
            trimmed.append(normalized)
        if trimmed:
            fields = self.dedup_strings(trimmed, 120)

        return self.build_result(
            "column",
            fields=fields,
            message="timeseries field extraction by udf",
        )
