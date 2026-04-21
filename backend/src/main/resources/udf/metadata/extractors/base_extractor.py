import abc
import json
import re


class BaseMetadataExtractor(object):
    __metaclass__ = abc.ABCMeta

    def __init__(self, data, args, params):
        self.data = data
        self.args = args
        self.params = params or {}
        self.logical_path = self._safe(self.params.get("logicalPath", ""))
        self.data_type = self._safe(self.params.get("dataType", "")).lower()

    @abc.abstractmethod
    def extract(self):
        raise NotImplementedError()

    def extract_fields(self):
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
        values = []
        self.flatten_values(self.data, values)
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
        import base64

        values = []
        self.flatten_values(self.data, values)
        for value in values:
            if isinstance(value, bytes) and len(value) > 32:
                return base64.b64encode(value).decode("utf-8")
        return ""

    def extract_keyvalue_keys(self, text_content):
        keys = []
        txt = (text_content or "").strip()
        if not txt:
            return keys

        try:
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

        response = client.chat.completions.create(
            model=model,
            temperature=0.1,
            messages=messages,
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
        while retry_count < max_retry and not parsed.get("triples", []):
            if not retry_messages:
                break
            retry_count += 1
            raw = self.llm_chat(model, retry_messages)
            parsed = self.parse_llm_json_payload(raw)

        return parsed, raw

    def parse_llm_json_payload(self, llm_text):
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
        triples = []

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

        return {
            "entities": self.dedup_strings(entities, 120),
            "triples": self.dedup_triples(triples, 180),
        }

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
        txt = self.to_text(value)
        if not txt:
            return ""
        txt = txt.strip().strip("()")
        if "." in txt:
            txt = txt.split(".")[-1]
        txt = re.sub(r"[^A-Za-z0-9_\-\u4e00-\u9fa5]", "", txt)
        return txt

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
            if len(out) >= max_count:
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
            if len(out) >= max_count:
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
