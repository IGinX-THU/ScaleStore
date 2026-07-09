import math


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


class _MetaInfoBase(object):
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

    def _to_binary(self, value):
        if value is None:
            return b""
        if isinstance(value, bytes):
            return value
        return str(value).encode("utf-8")


class _MetaInfoPassThrough(_MetaInfoBase):
    LOG_NAME = "meta_info"

    def transform(self, rows):
        records = self._records(rows)
        if not records:
            _trace(self.LOG_NAME + "_no_candidate")
            return [self.OUTPUT_COLUMNS]

        record = records[0]
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


class MetadataSemanticDirectoryMetaInfo(_MetaInfoPassThrough):
    LOG_NAME = "directory_meta_info"
