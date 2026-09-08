import abc
import json
import re
import time


class BaseMetadataExtractor(object):
    __metaclass__ = abc.ABCMeta
    # 图像原文和发送给视觉模型的最大体积分别受限，避免 UDF 因异常大文件耗尽内存或请求体。
    DEFAULT_MAX_RAW_IMAGE_BYTES = 64 * 1024 * 1024
    DEFAULT_MAX_VLM_IMAGE_BYTES = 4 * 1024 * 1024
    DEFAULT_MAX_VLM_IMAGE_SIDE = 1280
    DEFAULT_MIN_VLM_IMAGE_SIDE = 512
    DEFAULT_VLM_IMAGE_JPEG_QUALITY = 85

    def __init__(self, data, args, params):
        self.data = data
        self.args = args
        self.params = params or {}
        self.logical_path = self._safe(self.params.get("logicalPath", ""))
        self.data_type = self._safe(self.params.get("dataType", "")).lower()

    @abc.abstractmethod
    def extract(self):
        raise NotImplementedError()

    def build_result(self, field_kind, fields=None, keywords=None, entities=None, triples=None, message="",
                     skip_keyword_normalization=False):
        """Build the common raw-extraction contract consumed by the leaf semantic UDF."""
        result = {
            "fieldKind": field_kind,
            "fields": fields or [],
            "keywords": keywords or [],
            "entities": entities or [],
            "triples": triples or [],
            "message": message,
        }
        if skip_keyword_normalization:
            result["skipKeywordNormalization"] = True
        return result

    def extract_fields(self):
        # 优先将 IGinX 表头视为结构化字段；没有表头时才从扁平化值中做有限的兜底推断。
        fields = []
        if isinstance(self.data, list) and len(self.data) > 0 and isinstance(self.data[0], list):
            for item in self.data[0]:
                name = self.normalize_field_name(item)
                if name:
                    fields.append(name)

        if not fields:
            values = []
            self.flatten_values(self.data, values)
            for value in values:
                text = self.to_text(value)
                if "." in text and len(text) < 180:
                    parts = [part for part in text.split(".") if part and part != "data"]
                    if parts:
                        fields.append(parts[-1])

        return self.dedup_strings(fields, 120)

    def extract_text_content(self):
        # 文本内容有总长度和单值长度上限，控制传入规则解析与 LLM 的输入规模。
        values = []
        self.flatten_values(self.data_rows(), values)
        chunks = []
        for value in values:
            text = self.to_text(value)
            if not text:
                continue
            if len(text) > 8000:
                text = text[:8000]
            chunks.append(text)
            if len("\n".join(chunks)) > 15000:
                break
        return "\n".join(chunks)[:15000]

    def extract_first_binary_base64(self):
        image_bytes = self.extract_binary_bytes()
        if not image_bytes:
            return ""
        import base64
        return base64.b64encode(image_bytes).decode("utf-8")

    def extract_binary_bytes(self, max_bytes=None):
        if max_bytes is None:
            max_bytes = self._to_int(
                self.params.get("maxRawImageBytes", self.DEFAULT_MAX_RAW_IMAGE_BYTES),
                self.DEFAULT_MAX_RAW_IMAGE_BYTES,
            )
        if max_bytes <= 0:
            raise RuntimeError("maxRawImageBytes must be positive")

        # 只拼接足够大的 bytes 单元，过滤表头或普通短文本带来的伪二进制内容。
        values = []
        self.flatten_values(self.data_rows(), values)
        chunks = []
        total = 0
        for value in values:
            if isinstance(value, bytes) and len(value) > 32:
                total += len(value)
                if total > max_bytes:
                    raise RuntimeError("image bytes exceed maxRawImageBytes: " + str(total) + " > " + str(max_bytes))
                chunks.append(value)
        if not chunks:
            return b""
        return b"".join(chunks)

    def prepare_image_for_vlm(self):
        import base64

        image_bytes = self.extract_binary_bytes()
        if not image_bytes:
            return "", "", "image bytes not found"

        max_vlm_bytes = self._to_int(
            self.params.get("maxVlmImageBytes", self.DEFAULT_MAX_VLM_IMAGE_BYTES),
            self.DEFAULT_MAX_VLM_IMAGE_BYTES,
        )
        if max_vlm_bytes <= 0:
            raise RuntimeError("maxVlmImageBytes must be positive")

        original_mime = self._image_mime_type()
        # 原图合规则不重编码，保留可用于视觉语义识别的全部细节。
        if len(image_bytes) <= max_vlm_bytes:
            return base64.b64encode(image_bytes).decode("utf-8"), original_mime, "original image sent to vlm"

        compressed = self.compress_image_bytes(image_bytes, max_vlm_bytes)
        return base64.b64encode(compressed).decode("utf-8"), "image/jpeg", (
            "image compressed for vlm: " + str(len(image_bytes)) + " -> " + str(len(compressed)) + " bytes"
        )

    def compress_image_bytes(self, image_bytes, max_vlm_bytes):
        import io

        try:
            from PIL import Image, ImageOps
        except Exception as exc:
            raise RuntimeError("Pillow is required to compress large images before VLM extraction") from exc

        max_side = self._to_int(
            self.params.get("maxVlmImageSide", self.DEFAULT_MAX_VLM_IMAGE_SIDE),
            self.DEFAULT_MAX_VLM_IMAGE_SIDE,
        )
        min_side = self._to_int(
            self.params.get("minVlmImageSide", self.DEFAULT_MIN_VLM_IMAGE_SIDE),
            self.DEFAULT_MIN_VLM_IMAGE_SIDE,
        )
        quality = self._to_int(
            self.params.get("vlmImageJpegQuality", self.DEFAULT_VLM_IMAGE_JPEG_QUALITY),
            self.DEFAULT_VLM_IMAGE_JPEG_QUALITY,
        )

        with Image.open(io.BytesIO(image_bytes)) as image:
            image = ImageOps.exif_transpose(image)
            if getattr(image, "is_animated", False):
                image.seek(0)
            if image.mode not in ("RGB", "L"):
                image = image.convert("RGB")

            # 先在同一尺寸降低 JPEG 质量，再逐步缩小边长，尽量保留可辨识内容。
            side = max_side
            while side >= min_side:
                candidate = image.copy()
                candidate.thumbnail((side, side))
                q = quality
                while q >= 50:
                    out = io.BytesIO()
                    candidate.save(out, format="JPEG", quality=q, optimize=True)
                    data = out.getvalue()
                    if len(data) <= max_vlm_bytes:
                        return data
                    q -= 10
                side = int(side * 0.75)

        raise RuntimeError("compressed image still exceeds maxVlmImageBytes")

    def _image_mime_type(self):
        ext = self._safe(self.params.get("fileFormat", "")).lower().lstrip(".")
        if not ext:
            file_name = self._safe(self.params.get("fileName", "")).lower()
            if "." in file_name:
                ext = file_name.rsplit(".", 1)[-1]
        if ext == "jpg":
            ext = "jpeg"
        if ext in ("jpeg", "png", "bmp", "gif", "webp"):
            return "image/" + ext
        return "image/png"

    def data_rows(self):
        if (
            isinstance(self.data, list)
            and len(self.data) >= 2
            and isinstance(self.data[0], list)
            and isinstance(self.data[1], list)
        ):
            # 兼容 IGinX 可选的类型行，确保数据类型名称不会被当作真实内容提取。
            start = 2 if self.has_type_row(self.data[0], self.data[1]) else 1
            return self.data[start:]
        return self.data

    def has_type_row(self, headers, candidate_row):
        if not isinstance(headers, list) or not isinstance(candidate_row, list):
            return False
        if len(candidate_row) != len(headers):
            return False

        known_types = set([
            "BINARY",
            "BOOLEAN",
            "INTEGER",
            "LONG",
            "FLOAT",
            "DOUBLE",
            "STRING",
            "DATE",
            "TIME",
            "TIMESTAMP",
        ])

        matched = 0
        for value in candidate_row:
            text = self.to_text(value).strip().upper()
            if text in known_types:
                matched += 1
        return matched > 0 and matched == len(candidate_row)

    def extract_keyvalue_keys(self, text_content):
        keys = []
        txt = (text_content or "").strip()
        if not txt:
            return keys

        try:
            # JSON 可递归保留层级 key；非 JSON 再降级使用键名模式匹配。
            obj = json.loads(txt)
            if isinstance(obj, dict):
                self.walk_json_keys(obj, "", keys)
                return self.dedup_strings(keys, 120)
        except Exception:
            pass

        for match in re.finditer(r'"([A-Za-z0-9_\-\.]+)"\s*:', txt):
            keys.append(match.group(1))
        return self.dedup_strings(keys, 120)

    def walk_json_keys(self, obj, prefix, out_keys):
        if not isinstance(obj, dict):
            return
        for key, value in obj.items():
            full = key if not prefix else (prefix + "." + key)
            out_keys.append(full)
            if isinstance(value, dict):
                self.walk_json_keys(value, full, out_keys)

    def llm_chat(self, model, messages):
        api_key = self._safe(self.params.get("llmApiKey", ""))
        base_url = self._normalize_openai_base_url(self._safe(self.params.get("llmBaseUrl", "")))
        if not api_key:
            raise RuntimeError("llmApiKey is empty")
        if not model:
            raise RuntimeError("llm model is empty")

        try:
            from openai import OpenAI
        except Exception as exc:
            raise RuntimeError("openai package is required: pip install openai") from exc

        if base_url:
            client = OpenAI(api_key=api_key, base_url=base_url)
        else:
            client = OpenAI(api_key=api_key)

        started = time.time()
        print(
            "[SemanticKeywordExtract][extractor] llm_chat_start metaKey=%s dataType=%s model=%s"
            % (
                self._safe(self.params.get("metaKey", "")),
                self.data_type,
                model,
            ),
            flush=True,
        )
        response = client.chat.completions.create(
            model=model,
            temperature=0.1,
            messages=messages,
        )
        print(
            "[SemanticKeywordExtract][extractor] llm_chat_done metaKey=%s dataType=%s model=%s elapsedMs=%s"
            % (
                self._safe(self.params.get("metaKey", "")),
                self.data_type,
                model,
                int((time.time() - started) * 1000),
            ),
            flush=True,
        )

        content = ""
        if response and response.choices and response.choices[0].message:
            content = self.extract_text_from_content(response.choices[0].message.content)
        return content

    def llm_extract_with_retry(self, model, primary_messages, retry_messages=None, max_retry=1):
        """
        Strict extraction behavior:
        - run once with primary prompt
        - if triples are empty, retry with retry prompt (at most max_retry times)
        """
        raw = self.llm_chat(model, primary_messages)
        parsed = self.parse_llm_json_payload(raw)

        retry_count = 0
        # 重试以三元组为空为条件：关键词可为空，但需要再给模型一次补充关系的机会。
        while retry_count < max_retry and not parsed.get("triples", []):
            if not retry_messages:
                break
            retry_count += 1
            raw = self.llm_chat(model, retry_messages)
            parsed = self.parse_llm_json_payload(raw)

        return parsed, raw

    def parse_llm_json_payload(self, llm_text, classify_image=False):
        # 清理常见模型包装后再解析；解析失败返回空结构，调用方可安全继续走回退路径。
        cleaned = self.strip_think(llm_text or "")
        cleaned = self.strip_code_fence(cleaned)

        try:
            node = json.loads(cleaned)
        except Exception:
            start = cleaned.find("{")
            end = cleaned.rfind("}")
            if start >= 0 and end > start:
                node = json.loads(cleaned[start:end + 1])
            else:
                return {"entities": [], "triples": []}

        entities = []
        keywords = []
        triples = []

        for keyword in node.get("keywords", []):
            if isinstance(keyword, str) and keyword.strip():
                keywords.append(keyword.strip())

        for ent in node.get("entities", []):
            if isinstance(ent, str) and ent.strip():
                entities.append(ent.strip())

        triple_source = node.get("triples", node.get("relations", []))
        if isinstance(triple_source, list):
            for triple in triple_source:
                if not isinstance(triple, dict):
                    continue
                subject = str(triple.get("subject", "")).strip()
                predicate = str(triple.get("predicate", triple.get("relation", ""))).strip()
                obj = str(triple.get("object", "")).strip()
                if subject and predicate and obj:
                    triples.append({"subject": subject, "predicate": predicate, "object": obj})

        industrial = classify_image and node.get("isIndustrialDrawing") is True
        result = {
            "keywords": self.dedup_strings(keywords, None if industrial else 80),
            "entities": self.dedup_strings(entities, None if industrial else 120),
            "triples": self.dedup_triples(triples, None if industrial else 180),
        }
        if classify_image:
            result["isIndustrialDrawing"] = industrial
        return result

    def extract_text_from_content(self, content):
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, dict):
                    if item.get("type") == "text":
                        parts.append(str(item.get("text", "")))
                    elif "content" in item:
                        parts.append(str(item.get("content", "")))
                else:
                    parts.append(str(item))
            return "\n".join(parts)
        return str(content)

    def strip_think(self, text):
        lower = text.lower()
        idx = lower.rfind("</think>")
        if idx >= 0:
            return text[idx + len("</think>"):]
        return text

    def strip_code_fence(self, text):
        txt = text.strip()
        if txt.startswith("```") and txt.endswith("```"):
            line_idx = txt.find("\n")
            if line_idx >= 0:
                txt = txt[line_idx + 1:-3]
        return txt.strip()

    def normalize_field_name(self, value):
        # 去掉查询别名、路径前缀及不适合作为字段名的字符，保持 IginX 字段展示稳定。
        txt = self.to_text(value)
        if not txt:
            return ""
        txt = txt.strip().strip("()")
        if "." in txt:
            txt = txt.split(".")[-1]
        txt = re.sub(r"[^A-Za-z0-9_\-\u4e00-\u9fa5]", "", txt)
        return txt

    def normalize_iginx_duplicate_key_name(self, value):
        text = self._safe(value)
        if text.lower() == "key_2":
            return "key"
        return text

    def _to_int(self, value, default_value):
        try:
            return int(str(value).strip())
        except Exception:
            return default_value

    def flatten_values(self, node, out_values):
        if isinstance(node, dict):
            for val in node.values():
                self.flatten_values(val, out_values)
            return
        if isinstance(node, list):
            for item in node:
                self.flatten_values(item, out_values)
            return
        out_values.append(node)

    def to_text(self, value):
        if value is None:
            return ""
        if isinstance(value, bytes):
            try:
                return value.decode("utf-8", errors="ignore")
            except Exception:
                return ""
        return str(value)

    def dedup_strings(self, values, max_count):
        seen = set()
        out = []
        for value in values:
            text = str(value).strip()
            if not text or text in seen:
                continue
            seen.add(text)
            out.append(text)
            if max_count is not None and len(out) >= max_count:
                break
        return out

    def dedup_triples(self, triples, max_count):
        seen = set()
        out = []
        for triple in triples:
            if not isinstance(triple, dict):
                continue
            subject = str(triple.get("subject", "")).strip()
            predicate = str(triple.get("predicate", triple.get("relation", ""))).strip()
            obj = str(triple.get("object", "")).strip()
            if not subject or not predicate or not obj:
                continue
            key = subject + "|" + predicate + "|" + obj
            if key in seen:
                continue
            seen.add(key)
            out.append({"subject": subject, "predicate": predicate, "object": obj})
            if max_count is not None and len(out) >= max_count:
                break
        return out

    def get_root_entity(self):
        if self.logical_path:
            return self.logical_path
        return self.data_type if self.data_type else "data_asset"

    def _normalize_openai_base_url(self, base_url):
        url = (base_url or "").strip().rstrip("/")
        lower = url.lower()
        suffix = "/chat/completions"
        if lower.endswith(suffix):
            url = url[: -len(suffix)]
        return url

    def _safe(self, value):
        if value is None:
            return ""
        return str(value).strip()
