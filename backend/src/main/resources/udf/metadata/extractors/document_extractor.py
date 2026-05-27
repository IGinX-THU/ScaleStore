from .base_extractor import BaseMetadataExtractor


class DocumentMetadataExtractor(BaseMetadataExtractor):
    SYSTEM_PROMPT = "你是一个信息抽取助手。仅输出 JSON。"

    USER_PROMPT_TEMPLATE = (
        "请从输入的文档文本中抽取语义三元组。 "
        "尽可能提取代表元数据的语义，而非具体的数值或事实。 "
        "请严格只返回 JSON，不要输出 Markdown 或解释。 "
        "返回格式要求："
        "{{\"entities\":[\"entity\"],\"triples\":[{{\"subject\":\"entityA\",\"predicate\":\"relation\",\"object\":\"entityB\"}}]}}. "
        "谓词请尽量简洁。 "
        "logicalPath={logical_path}\n"
        "text={text_content}"
    )

    USER_RETRY_PROMPT_TEMPLATE = (
        "你上一轮可能没有返回可用三元组。请再次检查内容并尽量抽取核心语义关系；尽可能提取代表元数据的语义，而非具体的数值或事实；若确实无关系，triples 返回空数组。 "
        "请严格只返回 JSON，不要输出 Markdown 或解释。 "
        "返回格式要求："
        "{{\"entities\":[\"entity\"],\"triples\":[{{\"subject\":\"entityA\",\"predicate\":\"relation\",\"object\":\"entityB\"}}]}}. "
        "谓词请尽量简洁。 "
        "logicalPath={logical_path}\n"
        "text={text_content}"
    )

    def extract(self):
        if str(self.params.get("llmEnabled", "true")).strip().lower() != "true":
            raise RuntimeError("document extraction requires llmEnabled=true")

        text_content = self.extract_text_content()
        if not text_content:
            return {
                "fieldKind": "field",
                "fields": [],
                "entities": [],
                "triples": [],
                "message": "document content is empty",
            }

        model = str(self.params.get("llmModel", "")).strip()
        if not model:
            raise RuntimeError("llmModel is empty")

        user_prompt = self.USER_PROMPT_TEMPLATE.format(
            logical_path=self.logical_path,
            text_content=text_content[:8000],
        )

        retry_prompt = self.USER_RETRY_PROMPT_TEMPLATE.format(
            logical_path=self.logical_path,
            text_content=text_content[:8000],
        )

        messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ]

        retry_messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            {"role": "user", "content": retry_prompt},
        ]

        parsed, _ = self.llm_extract_with_retry(model, messages, retry_messages, max_retry=1)

        return {
            "fieldKind": "field",
            # Legacy behavior for document/image only keeps semantic entities/triples.
            "fields": [],
            "entities": parsed.get("entities", []),
            "triples": parsed.get("triples", []),
            "message": "document llm extraction completed",
        }
