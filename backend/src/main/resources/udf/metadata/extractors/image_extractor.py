from .base_extractor import BaseMetadataExtractor


class ImageMetadataExtractor(BaseMetadataExtractor):
    SYSTEM_PROMPT = "You are a multimodal metadata extraction assistant. Return strict JSON only."

    USER_PROMPT_TEXT = (
        "Extract metadata semantics from this image. Return strict JSON only. "
        "All keywords, entities, and predicates must be concise Chinese. "
        "Required shape: "
        "{\"keywords\":[\"中文关键词\"],\"entities\":[\"中文实体\"],"
        "\"triples\":[{\"subject\":\"实体A\",\"predicate\":\"关系\",\"object\":\"实体B\"}]}. "
        "If no relation is supported, return an empty triples array."
    )

    USER_RETRY_PROMPT_TEXT = (
        "Re-check the image and return Chinese metadata keywords plus any supported semantic triples. "
        "If no relation is supported, return an empty triples array. "
        "Return strict JSON only: "
        "{\"keywords\":[\"中文关键词\"],\"entities\":[\"中文实体\"],"
        "\"triples\":[{\"subject\":\"实体A\",\"predicate\":\"关系\",\"object\":\"实体B\"}]}."
    )

    def extract(self):
        if str(self.params.get("llmEnabled", "true")).strip().lower() != "true":
            raise RuntimeError("image extraction requires llmEnabled=true")

        image_base64, image_mime, image_prepare_message = self.prepare_image_for_vlm()
        if not image_base64:
            return {
                "fieldKind": "field",
                "fields": [],
                "keywords": [],
                "entities": [],
                "triples": [],
                "message": "image bytes not found",
            }

        model = str(self.params.get("llmVisionModel", "")).strip() or str(self.params.get("llmModel", "")).strip()
        if not model:
            raise RuntimeError("llmVisionModel/llmModel is empty")

        image_url = "data:" + image_mime + ";base64," + image_base64
        messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": self.USER_PROMPT_TEXT + " logicalPath=" + self.logical_path},
                    {"type": "image_url", "image_url": {"url": image_url}},
                ],
            },
        ]

        retry_messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": self.USER_RETRY_PROMPT_TEXT + " logicalPath=" + self.logical_path},
                    {"type": "image_url", "image_url": {"url": image_url}},
                ],
            },
        ]

        parsed, _ = self.llm_extract_with_retry(model, messages, retry_messages, max_retry=1)

        keywords = parsed.get("keywords", [])
        if not keywords:
            keywords = parsed.get("entities", [])

        return {
            "fieldKind": "field",
            "fields": [],
            "keywords": keywords,
            "entities": parsed.get("entities", []),
            "triples": parsed.get("triples", []),
            "message": "image llm extraction completed; " + image_prepare_message,
        }
