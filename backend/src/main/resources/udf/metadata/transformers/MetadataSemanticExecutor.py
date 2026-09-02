import json
import math
import os
import time

import requests
from metadata.SemanticKeywordExtract import (
    DirectorySemanticKeywordExtract,
    DocumentSemanticKeywordExtract,
    FileSemanticKeywordExtract,
    KeyValueSemanticKeywordExtract,
    RelationalSemanticKeywordExtract,
    TimeSeriesSemanticKeywordExtract,
)


def _safe(value):
    if value is None:
        return ""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).decode("utf-8", errors="ignore").strip()
    if isinstance(value, memoryview):
        return value.tobytes().decode("utf-8", errors="ignore").strip()
    if isinstance(value, float) and math.isnan(value):
        return ""
    return str(value).strip()


def _decode(value):
    if value is None:
        return ""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).decode("utf-8", errors="ignore").strip()
    if isinstance(value, memoryview):
        return value.tobytes().decode("utf-8", errors="ignore").strip()
    if isinstance(value, float) and math.isnan(value):
        return ""
    return str(value).strip()


def _normalize_column(name):
    text = _decode(name).strip().strip("`")
    if "(" in text and text.endswith(")"):
        inner = text[text.find("(") + 1:-1].strip().strip("`")
        if inner and inner != "*":
            text = inner
    text = text.strip().strip("`")
    if "." in text:
        text = text.split(".")[-1]
    return text.strip().strip("`").strip("()")


def _to_int(value, default_value):
    text = _safe(value)
    if not text:
        return default_value
    try:
        return int(float(text))
    except Exception:
        return default_value


def _trace(message, **fields):
    payload = []
    for key in sorted(fields.keys()):
        value = fields.get(key)
        payload.append(str(key) + "=" + _safe(value))
    suffix = " " + " ".join(payload) if payload else ""
    print("[MetadataSemanticTransform][executor] " + message + suffix, flush=True)


class _ExecutorBase(object):
    RESULT_COLUMNS = ["metaKey", "status", "keywords", "message"]
    DEFAULT_CALLBACK_BASE_URL = "http://127.0.0.1:8080"
    CALLBACK_CONFIG_ENV = "METADATA_CONFIG_FILE"
    CALLBACK_CONFIG_NAME = "config.json"

    def _result(self, meta_key, status, keywords, message):
        return [self.RESULT_COLUMNS, [
            meta_key,
            self._to_binary(status),
            self._to_binary(keywords),
            self._to_binary(message),
        ]]

    def _empty(self):
        return [self.RESULT_COLUMNS]

    def _load_callback_config(self):
        path = os.environ.get(self.CALLBACK_CONFIG_ENV)
        if not path:
            path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), self.CALLBACK_CONFIG_NAME)
        if not os.path.exists(path):
            return {}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            return data if isinstance(data, dict) else {}
        except Exception as exc:
            _trace("callback_config_load_failed", error=str(exc))
            return {}

    def _callback_url(self, kind):
        # 与 MetaInfo 使用相同的 URL 解析逻辑，确保开始和完成回调落到同一个后端实例。
        config = self._load_callback_config()
        direct_key = kind + "SemanticCallbackUrl"
        direct_url = _safe(config.get(direct_key, ""))
        if direct_url:
            return direct_url
        webserver = config.get("webserver", {})
        if isinstance(webserver, dict):
            direct_url = _safe(webserver.get(direct_key, ""))
            if direct_url:
                return direct_url
        base_url = _safe(config.get("callbackBaseUrl", ""))
        if not base_url and isinstance(webserver, dict):
            base_url = _safe(webserver.get("baseUrl", ""))
        if not base_url:
            base_url = self.DEFAULT_CALLBACK_BASE_URL
        return base_url.rstrip("/") + "/metadata/extraction/semantic/" + kind + "-callback"

    def _post_callback(self, kind, payload):
        # 完成回调是语义关键字写回 storage.meta 的唯一通道，非 2xx 或业务拒绝都必须让任务失败。
        url = self._callback_url(kind)
        response = requests.post(url, json=payload, timeout=30)
        body = response.text
        if response.status_code < 200 or response.status_code >= 300:
            _trace(
                kind + "_callback_failed",
                metaKey=payload.get("metaKey", ""),
                statusCode=response.status_code,
                bodyPreview=body[:160],
            )
            raise RuntimeError("%s semantic callback failed: status=%s body=%s" % (kind, response.status_code, body[:400]))
        try:
            parsed = response.json()
        except Exception:
            return
        code = parsed.get("code")
        if code is not None and int(code) != 200:
            _trace(
                kind + "_callback_rejected",
                metaKey=payload.get("metaKey", ""),
                code=code,
                message=_safe(parsed.get("message", "")),
            )
            raise RuntimeError("%s semantic callback rejected: code=%s message=%s" % (kind, code, _safe(parsed.get("message", ""))))

    def _parse_udf_result(self, payload):
        # UDF 返回固定的“表头、类型、数据”二维表；解析失败统一转换成可回调的 FAILED 结果。
        if not isinstance(payload, list) or len(payload) < 3:
            return "FAILED", "[]", "empty udf result"
        row = payload[-1] if isinstance(payload[-1], list) else []
        status = _safe(row[0] if len(row) > 0 else "").upper()
        keywords = _safe(row[1] if len(row) > 1 else "")
        message = _safe(row[4] if len(row) > 4 else "")
        if not status:
            status = "FAILED"
        if not keywords:
            keywords = "[]"
        return status, keywords, message

    def _to_binary(self, value):
        if value is None:
            return b""
        if isinstance(value, bytes):
            return value
        return str(value).encode("utf-8")

    def _parse_rows(self, rows):
        if not isinstance(rows, list) or not rows or not isinstance(rows[0], list):
            return [], []

        # JOIN 后包含原始内容列和 metaInfo_/dirMeta_ 元数据列，保留全部列供后续拆分。
        headers = self._dedup_headers([_normalize_column(v) for v in rows[0]])
        data_rows = []
        start = 1
        if len(rows) > 1 and isinstance(rows[1], list) and self._is_type_row(headers, rows[1]):
            start = 2
        for raw in rows[start:]:
            if not isinstance(raw, list):
                continue
            normalized = []
            for idx in range(len(headers)):
                normalized.append(raw[idx] if idx < len(raw) else None)
            data_rows.append(normalized)
        return headers, data_rows

    def _dedup_headers(self, headers):
        out = []
        used = set()
        for header in headers:
            candidate = _safe(header)
            if not candidate:
                candidate = "column_" + str(len(out))
            base = candidate
            suffix = 2
            while candidate in used:
                candidate = base + "_" + str(suffix)
                suffix += 1
            used.add(candidate)
            out.append(candidate)
        return out

    def _find_prefixed_record(self, headers, row, prefix):
        # 元数据列由工作流 SQL 加前缀，防止与内容表中的同名字段发生碰撞。
        record = {}
        has_value = False
        for idx, header in enumerate(headers):
            if not header.startswith(prefix):
                continue
            key = header[len(prefix):]
            value = row[idx] if idx < len(row) else None
            record[key] = _decode(value)
            if _safe(record[key]):
                has_value = True
        return record if has_value else None

    def _is_type_row(self, headers, row):
        if not isinstance(headers, list) or not isinstance(row, list):
            return False
        if len(headers) != len(row):
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
        for value in row:
            text = _decode(value).strip().upper()
            if text in known_types:
                matched += 1
        return matched > 0 and matched == len(row)

    def _keyword_count(self, keywords):
        try:
            parsed = json.loads(keywords)
            if isinstance(parsed, list):
                return len(parsed)
        except Exception:
            pass
        return 0


class MetadataSemanticLeafExecutor(_ExecutorBase):
    TYPE_TO_UDF = {
        "relational": RelationalSemanticKeywordExtract,
        "timeseries": TimeSeriesSemanticKeywordExtract,
        "keyvalue": KeyValueSemanticKeywordExtract,
        "document": DocumentSemanticKeywordExtract,
        "file": FileSemanticKeywordExtract,
    }

    def transform(self, rows):
        started = time.time()
        headers, data_rows = self._parse_rows(rows)
        if not headers or not data_rows:
            return self._empty()

        meta = None
        for row in data_rows:
            meta = self._find_prefixed_record(headers, row, "metaInfo_")
            if meta is not None:
                break
        if meta is None:
            _trace("leaf_executor_meta_missing")
            return self._empty()

        data_indices = []
        data_headers = []
        for idx, header in enumerate(headers):
            if header.startswith("metaInfo_"):
                continue
            data_indices.append(idx)
            data_headers.append(header)

        # 向具体提取 UDF 只传内容矩阵；metaInfo_ 列仅作为运行参数，不应被识别成业务字段。
        matrix = [data_headers]
        for row in data_rows:
            matrix.append([row[idx] if idx < len(row) else None for idx in data_indices])

        data_type = _safe(meta.get("dataType", "")).lower()
        udf_cls = self.TYPE_TO_UDF.get(data_type)
        meta_key = _to_int(meta.get("key", meta.get("metaKey", "")), 0)
        _trace(
            "leaf_executor_start",
            metaKey=meta_key,
            dataType=data_type,
            fileName=meta.get("fileName", ""),
            dataRows=len(matrix) - 1,
        )
        if udf_cls is None:
            _trace("leaf_executor_unsupported_type", metaKey=meta_key, dataType=data_type)
            return self._result(meta_key, "FAILED", "[]", "unsupported metadata dataType: " + data_type)

        kvargs = {
            "logicalPath": _safe(meta.get("logicalPath", "")),
            "metaKey": str(meta_key),
            "fileName": _safe(meta.get("fileName", "")),
            "dataType": data_type,
            "fileFormat": _safe(meta.get("fileFormat", "")),
            "fileSize": _safe(meta.get("fileSize", "")),
            "createTime": _safe(meta.get("createTime", "")),
        }
        udf_started = time.time()
        try:
            status, keywords, message = self._parse_udf_result(udf_cls().transform(matrix, None, kvargs))
        except Exception as exc:
            _trace(
                "leaf_udf_exception",
                metaKey=meta_key,
                dataType=data_type,
                elapsedMs=int((time.time() - udf_started) * 1000),
                error=str(exc),
            )
            raise
        # 语义 UDF 已写 Neo4j；该回调负责把 status、keywords 和诊断消息写回 IginX 元数据表。
        callback_payload = {
            "metaKey": meta_key,
            "status": status,
            "keywords": keywords,
            "message": message,
            "logicalPath": _safe(meta.get("logicalPath", "")),
            "assetPath": _safe(meta.get("assetPath", "")),
            "fileName": _safe(meta.get("fileName", "")),
            "dataType": data_type,
            "fileFormat": _safe(meta.get("fileFormat", "")),
        }
        self._post_callback("leaf", callback_payload)

        _trace(
            "leaf_executor_done",
            metaKey=meta_key,
            status=status,
            keywordCount=self._keyword_count(keywords),
            udfElapsedMs=int((time.time() - udf_started) * 1000),
            totalElapsedMs=int((time.time() - started) * 1000),
        )
        return self._result(meta_key, status, keywords, message)


class MetadataSemanticDirectoryExecutor(_ExecutorBase):
    def transform(self, rows):
        started = time.time()
        headers, data_rows = self._parse_rows(rows)
        if not headers or not data_rows:
            _trace(
                "directory_executor_empty_input",
                rawRows=len(rows) if isinstance(rows, list) else 0,
            )
            return self._empty()

        meta = None
        for row in data_rows:
            meta = self._find_prefixed_record(headers, row, "dirMeta_")
            if meta is not None:
                break
        if meta is None:
            _trace(
                "directory_executor_meta_missing",
                rows=len(data_rows),
                headers="|".join(headers[:24]),
            )
            return self._empty()

        meta_key = _to_int(meta.get("key", meta.get("metaKey", "")), 0)
        _trace(
            "directory_executor_start",
            metaKey=meta_key,
            dataType="directory",
            fileName=meta.get("fileName", ""),
            dataRows=len(data_rows),
        )
        kvargs = {
            "logicalPath": _safe(meta.get("logicalPath", "")),
            "metaKey": str(meta_key),
            "fileName": _safe(meta.get("fileName", "")),
            "dataType": "directory",
            "fileFormat": "",
            "fileSize": "0",
            "createTime": _safe(meta.get("createTime", "")),
        }

        matrix = [headers]
        matrix.extend(data_rows)
        udf_started = time.time()
        try:
            udf_payload = DirectorySemanticKeywordExtract().transform(matrix, None, kvargs)
            status, keywords, message = self._parse_udf_result(udf_payload)
        except Exception as exc:
            _trace(
                "directory_udf_exception",
                metaKey=meta_key,
                elapsedMs=int((time.time() - udf_started) * 1000),
                error=str(exc),
            )
            raise
        if status == "SUCCESS":
            try:
                json.loads(keywords)
            except Exception:
                status = "FAILED"
                keywords = "[]"
                message = "invalid directory keyword payload"

        callback_payload = {
            "metaKey": meta_key,
            "status": status,
            "keywords": keywords,
            "message": message,
            "logicalPath": _safe(meta.get("logicalPath", "")),
            "assetPath": _safe(meta.get("assetPath", "")),
            "fileName": _safe(meta.get("fileName", "")),
            "dataType": "directory",
            "fileFormat": "",
        }
        self._post_callback("directory", callback_payload)

        _trace(
            "directory_executor_done",
            metaKey=meta_key,
            status=status,
            keywordCount=self._keyword_count(keywords),
            udfElapsedMs=int((time.time() - udf_started) * 1000),
            totalElapsedMs=int((time.time() - started) * 1000),
        )
        return self._result(meta_key, status, keywords, message)
