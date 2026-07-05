from .base_extractor import BaseMetadataExtractor
from .image_extractor import ImageMetadataExtractor


class FileMetadataExtractor(BaseMetadataExtractor):
    IMAGE_EXTENSIONS = set(["jpg", "jpeg", "png", "bmp", "gif", "webp"])
    SYSTEM_PROMPT = "You are a file metadata extraction assistant. Return strict JSON only."
    USER_PROMPT_TEMPLATE = (
        "Extract semantic metadata from this file content. "
        "Return strict JSON only. All keywords, entities, and predicates must be concise Chinese. "
        "Required shape: "
        "{{\"keywords\":[\"中文关键词\"],\"entities\":[\"中文实体\"],"
        "\"triples\":[{{\"subject\":\"实体A\",\"predicate\":\"关系\",\"object\":\"实体B\"}}]}}. "
        "Prefer metadata-level topics, document purpose, domain objects, measurements, and business concepts. "
        "If no relation is supported, return an empty triples array.\n"
        "logicalPath={logical_path}\n"
        "fileName={file_name}\n"
        "content={text_content}"
    )

    def extract(self):
        file_format = self._file_format()
        if file_format in self.IMAGE_EXTENSIONS:
            return ImageMetadataExtractor(self.data, self.args, self.params).extract()

        return self._fallback("non-image file semantic extraction skipped")

    def _fallback(self, message):
        return {
            "fieldKind": "field",
            "fields": [],
            "keywords": [],
            "entities": [],
            "triples": [],
            "message": message,
            "skipKeywordNormalization": True,
        }

    def _file_format(self):
        text = self._safe(self.params.get("fileFormat", "")).lower().lstrip(".")
        if text:
            return text
        file_name = self._safe(self.params.get("fileName", "")).lower()
        if "." in file_name:
            return file_name.rsplit(".", 1)[-1]
        return ""
