import json
import math
import os

import requests


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


def _normalize_path(value):
    path = _safe(value)
    if not path:
        return "/"
    if not path.startswith("/"):
        path = "/" + path
    while "//" in path:
        path = path.replace("//", "/")
    while len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


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


def _is_type_row(headers, row):
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
        if _decode(value).strip().upper() in known_types:
            matched += 1
    return matched > 0 and matched == len(row)


def _trace(message, **fields):
    payload = []
    for key in sorted(fields.keys()):
        value = fields.get(key)
        payload.append(str(key) + "=" + _safe(value))
    suffix = " " + " ".join(payload) if payload else ""
    print("[MetadataSemanticTransform][meta-info] " + message + suffix, flush=True)


class _CallbackSupport(object):
    DEFAULT_CALLBACK_BASE_URL = "http://127.0.0.1:8080"
    CALLBACK_CONFIG_ENV = "METADATA_CONFIG_FILE"
    CALLBACK_CONFIG_NAME = "config.json"

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
        # 支持按回调类型覆写 URL；未配置时退回到同一 Web 服务的标准回调路由。
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
        # STARTED 回调把元数据状态置为 PROCESSING，避免调度器在 UDF 执行期间重复选中同一项。
        url = self._callback_url(kind)
        response = requests.post(url, json=payload, timeout=10)
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


class _MetaInfoBase(_CallbackSupport):
    OUTPUT_COLUMNS = [
        "metaKey",
        "logicalPath",
        "assetPath",
        "fileName",
        "dataType",
        "fileFormat",
        "fileSize",
        "createTime",
        "contentPath",
    ]

    def _records(self, rows):
        if not isinstance(rows, list) or not rows:
            return []

        headers = []
        start = 0
        if isinstance(rows[0], list):
            # 将 IGinX 行列结果标准化为字典，后续流程不依赖 SQL 中的列前缀或二进制表现形式。
            headers = self._dedup_headers([_normalize_column(v) for v in rows[0]])
            if headers:
                start = 1
                if len(rows) > 1 and isinstance(rows[1], list) and _is_type_row(headers, rows[1]):
                    start = 2

        records = []
        for raw in rows[start:]:
            if not isinstance(raw, list):
                continue
            record = {}
            for idx, header in enumerate(headers):
                if idx < len(raw):
                    record[header] = _decode(raw[idx])
                else:
                    record[header] = ""
            if any(_safe(value) for value in record.values()):
                records.append(record)
        return records

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

    def _asset_path(self, item):
        logical_path = _normalize_path(item.get("logicalPath", ""))
        file_name = _safe(item.get("fileName", ""))
        if not file_name:
            return logical_path
        if logical_path.endswith("/" + file_name):
            return logical_path
        return _normalize_path(logical_path + "/" + file_name)

    def _emit(self, item):
        if item is None:
            return [self.OUTPUT_COLUMNS]
        # 输出列名是工作流 JOIN 的契约，字段顺序不可随意调整。
        return [self.OUTPUT_COLUMNS, [
            _to_int(item.get("key", item.get("metaKey", "")), 0),
            self._to_binary(_normalize_path(item.get("logicalPath", ""))),
            self._to_binary(self._asset_path(item)),
            self._to_binary(_safe(item.get("fileName", ""))),
            self._to_binary(_safe(item.get("dataType", "")).lower()),
            self._to_binary(_safe(item.get("fileFormat", ""))),
            self._to_binary(_safe(item.get("fileSize", ""))),
            self._to_binary(_safe(item.get("createTime", ""))),
            self._to_binary(_safe(item.get("contentPath", ""))),
        ]]

    def _notify_started(self, item):
        # 在读取原始内容之前发送状态回调；异常时由后续 Executor 的失败结果覆盖状态。
        meta_key = _to_int(item.get("key", item.get("metaKey", "")), 0)
        logical_path = _normalize_path(item.get("logicalPath", ""))
        asset_path = self._asset_path(item)
        data_type = _safe(item.get("dataType", "")).lower()
        payload = {
            "metaKey": meta_key,
            "status": "STARTED",
            "keywords": "[]",
            "message": "semantic extraction started",
            "logicalPath": logical_path,
            "assetPath": asset_path,
            "fileName": _safe(item.get("fileName", "")),
            "dataType": data_type,
            "fileFormat": _safe(item.get("fileFormat", "")),
        }
        self._post_callback(self.CALLBACK_KIND, payload)
        _trace(
            self.LOG_NAME + "_started_callback_sent",
            metaKey=meta_key,
            logicalPath=logical_path,
            assetPath=asset_path,
            dataType=data_type,
        )

    def _to_binary(self, value):
        if value is None:
            return b""
        if isinstance(value, bytes):
            return value
        return str(value).encode("utf-8")


class _MetaInfoPassThrough(_MetaInfoBase):
    LOG_NAME = "meta_info"
    CALLBACK_KIND = "leaf-start"

    def transform(self, rows):
        records = self._records(rows)
        if not records:
            _trace(self.LOG_NAME + "_no_candidate")
            return [self.OUTPUT_COLUMNS]

        # 调度 SQL 已限制为单个候选资产；这里只透传第一条，避免一次批处理产生多次状态转换。
        record = records[0]
        self._notify_started(record)
        output = self._emit(record)
        _trace(
            self.LOG_NAME + "_emit",
            metaKey=record.get("key", record.get("metaKey", "")),
            dataType=record.get("dataType", ""),
            fileName=record.get("fileName", ""),
            logicalPath=_normalize_path(record.get("logicalPath", "")),
            assetPath=self._asset_path(record),
        )
        return output


class MetadataSemanticLeafMetaInfo(_MetaInfoPassThrough):
    LOG_NAME = "leaf_meta_info"
    CALLBACK_KIND = "leaf-start"


class MetadataSemanticDirectoryMetaInfo(_MetaInfoPassThrough):
    LOG_NAME = "directory_meta_info"
    CALLBACK_KIND = "directory-start"
