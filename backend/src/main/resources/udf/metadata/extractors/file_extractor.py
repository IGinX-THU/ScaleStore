from .base_extractor import BaseMetadataExtractor


class FileMetadataExtractor(BaseMetadataExtractor):
    IMAGE_EXTENSIONS = set(["jpg", "jpeg", "png", "bmp", "gif", "webp"])
    IMAGE_SYSTEM_PROMPT = "You are a multimodal metadata extraction assistant. Return strict JSON only."
    IMAGE_PROMPT = (
        "Extract metadata semantics from this image. Return strict JSON only. "
        "All keywords, entities, and predicates must be concise Chinese. "
        "Required shape: "
        "{\"keywords\":[\"Chinese keyword\"],\"entities\":[\"Chinese entity\"],"
        "\"triples\":[{\"subject\":\"entity A\",\"predicate\":\"relation\",\"object\":\"entity B\"}]}. "
        "If no relation is supported, return an empty triples array."
    )
    IMAGE_RETRY_PROMPT = (
        "Re-check the image and return Chinese metadata keywords plus any supported semantic triples. "
        "If no relation is supported, return an empty triples array. "
        "Return strict JSON only: "
        "{\"keywords\":[\"Chinese keyword\"],\"entities\":[\"Chinese entity\"],"
        "\"triples\":[{\"subject\":\"entity A\",\"predicate\":\"relation\",\"object\":\"entity B\"}]}"
    )

    def extract(self):
        if self._file_format() not in self.IMAGE_EXTENSIONS:
            # 当前文件类型只提取图像；其他二进制格式不将不可读字节误作文本语义。
            return self.build_result(
                "field",
                message="non-image file semantic extraction skipped",
                skip_keyword_normalization=True,
            )
        return self._extract_image()

    def _extract_image(self):
        if str(self.params.get("llmEnabled", "true")).strip().lower() != "true":
            raise RuntimeError("image extraction requires llmEnabled=true")

        image_base64, image_mime, image_prepare_message = self.prepare_image_for_vlm()
        if not image_base64:
            return self.build_result("field", message="image bytes not found")

        model = self._safe(self.params.get("llmVisionModel", "")) or self._safe(self.params.get("llmModel", ""))
        if not model:
            raise RuntimeError("llmVisionModel/llmModel is empty")

        image_url = "data:" + image_mime + ";base64," + image_base64
        parsed, _ = self.llm_extract_with_retry(
            model,
            self._image_messages(self.IMAGE_PROMPT, image_url),
            self._image_messages(self.IMAGE_RETRY_PROMPT, image_url),
            max_retry=1,
        )
        keywords = parsed.get("keywords", []) or parsed.get("entities", [])
        return self.build_result(
            "field",
            keywords=keywords,
            entities=parsed.get("entities", []),
            triples=parsed.get("triples", []),
            message="image llm extraction completed; " + image_prepare_message,
        )

    def _image_messages(self, prompt, image_url):
        return [
            {"role": "system", "content": self.IMAGE_SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt + " logicalPath=" + self.logical_path},
                    {"type": "image_url", "image_url": {"url": image_url}},
                ],
            },
        ]

    def _file_format(self):
        text = self._safe(self.params.get("fileFormat", "")).lower().lstrip(".")
        if text:
            return text
        file_name = self._safe(self.params.get("fileName", "")).lower()
        if "." in file_name:
            return file_name.rsplit(".", 1)[-1]
        return ""
