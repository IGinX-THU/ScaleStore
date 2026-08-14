import math


OUTPUT_BASE_COLUMNS = [
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

OUTPUT_COLUMNS = [
    "metadata_semantic_directory_candidate(storage.meta." + column + ")" for column in OUTPUT_BASE_COLUMNS
]

OUTPUT_TYPES = [
    "LONG",
    "BINARY",
    "BINARY",
    "BINARY",
    "BINARY",
    "BINARY",
    "BINARY",
    "BINARY",
    "BINARY",
]


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
    if _looks_like_java_byte_array(value):
        try:
            return bytes([(int(item) + 256) % 256 for item in value]).decode("utf-8", errors="ignore").strip()
        except Exception:
            pass
    return str(value).strip()


def _looks_like_java_byte_array(value):
    class_name = value.__class__.__name__
    module_name = value.__class__.__module__
    return class_name in ("JavaArray", "byte[]") or "py4j" in module_name.lower()


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


def _parent_path(path):
    value = _normalize_path(path)
    if value == "/":
        return ""
    idx = value.rfind("/")
    if idx <= 0:
        return "/"
    return value[:idx]


def _asset_depth(path):
    value = _normalize_path(path)
    if value == "/":
        return 0
    return value.count("/")


def _to_int(value, default_value):
    text = _safe(value)
    if not text:
        return default_value
    try:
        return int(float(text))
    except Exception:
        return default_value


def _to_binary(value):
    if value is None:
        return ""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).decode("utf-8", errors="ignore")
    if isinstance(value, memoryview):
        return value.tobytes().decode("utf-8", errors="ignore")
    return str(value)


def _trace(message, **fields):
    payload = []
    for key in sorted(fields.keys()):
        payload.append(str(key) + "=" + _safe(fields.get(key)))
    suffix = " " + " ".join(payload) if payload else ""
    print("[MetadataSemanticTransform][directory-candidate] " + message + suffix, flush=True)


class MetadataSemanticDirectoryCandidate(object):
    def transform(self, data, args, kvargs):
        # 工作流每轮只返回一个目录，保证目录关键字按“子节点已完成”顺序自底向上生成。
        records = self._records(data)
        selected = self._select(records)
        if selected is None:
            return [list(OUTPUT_COLUMNS), list(OUTPUT_TYPES)]

        output = [list(OUTPUT_COLUMNS), list(OUTPUT_TYPES), self._emit_row(selected)]
        _trace(
            "directory_candidate_selected",
            metaKey=selected.get("key", selected.get("metaKey", "")),
        )
        return output

    def _records(self, rows):
        if not isinstance(rows, list) or not rows:
            return []

        headers = []
        start = 0
        if isinstance(rows[0], list):
            # IGinX 的 Python UDF 输入可能带有表头和类型行；下面统一转换为字段名到值的记录。
            headers = self._dedup_headers([_normalize_column(v) for v in rows[0]])
            start = 1
            if len(rows) > 1 and isinstance(rows[1], list) and self._is_type_row(headers, rows[1]):
                start = 2

        if not headers:
            headers = [
                "key",
                "logicalPath",
                "dataType",
                "fileName",
                "contentPath",
                "fileSize",
                "fileFormat",
                "createTime",
                "isValid",
                "knowledgeExtractStatus",
                "semanticKeywords",
            ]

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

    def _select(self, records):
        # 深层目录优先：先让最内层目录完成汇总，父目录才能可靠地使用其直接子节点关键字。深度越大，metaKey数值越小，排得越前。
        sorted_items = sorted(records, key=self._sort_key)
        pending_directory = None
        retry_directory = None
        for item in sorted_items:
            if not self._is_directory(item):
                continue
            # 1. 满足状态条件：PENDING or FAILED or (SUCCESS and semanticKeywords 为空)
            missing_keywords = not _safe(item.get("semanticKeywords", ""))
            eligible_status = self._is_pending_or_failed(item) or (
                self._status(item) == "SUCCESS" and missing_keywords
            )
            # 2. 满足子项已经完成：_directory_children_ready_with_reason
            if not (eligible_status and self._directory_children_ready(item, records)):
                continue
            # 判断是正常待处理的(Pending)，还是失败重试的(Failed)
            if self._is_pending(item) or (self._status(item) == "SUCCESS" and missing_keywords):
                if pending_directory is None:
                    pending_directory = item
            elif self._is_failed(item) and retry_directory is None:
                retry_directory = item

        # 正常待处理项优先于失败重试项，避免重试占用持续到来的新任务。
        return pending_directory if pending_directory is not None else retry_directory

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
            if _decode(value).strip().upper() in known_types:
                matched += 1
        return matched > 0 and matched == len(row)

    def _status(self, item):
        status = _safe(item.get("knowledgeExtractStatus", "")).upper()
        return status if status else "PENDING"

    def _is_pending_or_failed(self, item):
        status = self._status(item)
        return status == "PENDING" or status == "FAILED"

    def _is_pending(self, item):
        return self._status(item) == "PENDING"

    def _is_failed(self, item):
        return self._status(item) == "FAILED"

    def _is_processing(self, item):
        return self._status(item) == "PROCESSING"

    def _is_directory(self, item):
        return _safe(item.get("dataType", "")).lower() == "directory"

    def _sort_key(self, item):
        return (-_asset_depth(self._asset_path(item)), _to_int(item.get("key", item.get("metaKey", "")), 2147483647))

    def _asset_path(self, item):
        logical_path = _normalize_path(item.get("logicalPath", ""))
        file_name = _safe(item.get("fileName", ""))
        if not file_name:
            return logical_path
        if logical_path.endswith("/" + file_name):
            return logical_path
        return _normalize_path(logical_path + "/" + file_name)

    def _directory_children_ready(self, directory, all_items):
        ready, _ = self._directory_children_ready_with_reason(directory, all_items)
        return ready

    def _directory_children_ready_with_reason(self, directory, all_items):
        dir_path = self._asset_path(directory)
        has_child = False
        for item in all_items:
            if item is directory:
                continue
            child_path = self._asset_path(item)
            # 目录摘要只消费直接子项；孙级目录会先独立产出自己的摘要。
            if _parent_path(child_path) != dir_path:
                continue
            has_child = True
            status = self._status(item)
            keywords = _safe(item.get("semanticKeywords", ""))
            # SKIPPED 不提供语义，但不阻塞其他可提取子项形成目录摘要。
            if status == "SKIPPED":
                continue
            if status != "SUCCESS" or not keywords:
                return False, "child_not_ready:%s:%s:%s" % (
                    _safe(item.get("key", item.get("metaKey", ""))),
                    status,
                    keywords[:64],
                )
        if not has_child:
            return False, "no_child"
        return True, "ready"

    def _emit_row(self, item):
        return [
            _to_int(item.get("key", item.get("metaKey", "")), 0),
            _to_binary(_normalize_path(item.get("logicalPath", ""))),
            _to_binary(self._asset_path(item)),
            _to_binary(_safe(item.get("fileName", ""))),
            _to_binary(_safe(item.get("dataType", "")).lower()),
            _to_binary(_safe(item.get("fileFormat", ""))),
            _to_binary(_safe(item.get("fileSize", ""))),
            _to_binary(_safe(item.get("createTime", ""))),
            _to_binary(_safe(item.get("contentPath", ""))),
        ]
