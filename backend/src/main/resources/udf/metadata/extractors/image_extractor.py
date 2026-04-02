from .base_extractor import BaseMetadataExtractor


class ImageMetadataExtractor(BaseMetadataExtractor):
    SYSTEM_PROMPT = "你是一个多模态信息抽取助手。仅输出 JSON。"

    USER_PROMPT_TEXT = (
        "请从图像中抽取语义三元组。 "
        "请严格只返回 JSON，不要输出 Markdown 或解释。 "
        "返回格式要求："
        "{\"entities\":[\"entity\"],\"triples\":[{\"subject\":\"entityA\",\"predicate\":\"relation\",\"object\":\"entityB\"}]}. "
        "如果关系抽取失败，请返回空的 triples 数组。"
    )

    USER_RETRY_PROMPT_TEXT = (
        "你上一轮可能没有返回可用三元组。请再次检查图像内容并尽量抽取核心语义关系；若确实无关系，triples 返回空数组。 "
        "请严格只返回 JSON，不要输出 Markdown 或解释。 "
        "返回格式要求："
        "{\"entities\":[\"entity\"],\"triples\":[{\"subject\":\"entityA\",\"predicate\":\"relation\",\"object\":\"entityB\"}]}."
    )

    def extract(self):
        if str(self.params.get("llmEnabled", "true")).strip().lower() != "true":
            raise RuntimeError("image extraction requires llmEnabled=true")

        image_base64 = self.extract_first_binary_base64()
        if not image_base64:
            return {
                "fieldKind": "field",
                "fields": [],
                "entities": [],
                "triples": [],
                "message": "image bytes not found",
            }

        model = str(self.params.get("llmVisionModel", "")).strip() or str(self.params.get("llmModel", "")).strip()
        if not model:
            raise RuntimeError("llmVisionModel/llmModel is empty")

        messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": self.USER_PROMPT_TEXT + " logicalPath=" + self.logical_path},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + image_base64}},
                ],
            },
        ]

        retry_messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": self.USER_RETRY_PROMPT_TEXT + " logicalPath=" + self.logical_path},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + image_base64}},
                ],
            },
        ]

        parsed, _ = self.llm_extract_with_retry(model, messages, retry_messages, max_retry=1)

        return {
            "fieldKind": "field",
            # Legacy behavior for document/image only keeps semantic entities/triples.
            "fields": [],
            "entities": parsed.get("entities", []),
            "triples": parsed.get("triples", []),
            "message": "image llm extraction completed",
        }
