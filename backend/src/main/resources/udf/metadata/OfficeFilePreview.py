"""Read-only UDSF: complete file bytes -> first three page images. No LLM or metadata writes."""
import base64
import hashlib
import io
import json
import os
import re
import tempfile
import time
import traceback
import uuid
from contextlib import contextmanager
from pathlib import Path

from metadata.SemanticKeywordExtract import _RuntimeConfigMixin
from metadata.extractors.office_file_extractor import OFFICE_EXTENSIONS, OfficeFileMetadataExtractor

MAX_RESULT_BYTES = 9 * 1024 * 1024
RENDERER_VERSION = "pages-v1"
MAX_PAGES = 3
MAX_SIDE = 1600
MAX_PAGE_BYTES = 2 * 1024 * 1024


def _log(event, **fields):
    print("[OfficeFilePreview] " + json.dumps(dict(event=event, pid=os.getpid(), **fields),
                                            ensure_ascii=False, default=str), flush=True)


def _failure(event, request_id, exc):
    _log(event, requestId=request_id, errorType=type(exc).__name__, message=str(exc),
         traceback=traceback.format_exc())


class OfficeFilePreview(_RuntimeConfigMixin):
    def transform(self, data, args, kvargs):
        request_id = uuid.uuid4().hex[:12]
        started = time.monotonic()
        _log("preview_start", requestId=request_id)
        try:
            raw_extension = args[0] if args else ""
            if isinstance(raw_extension, bytes):
                raw_extension = raw_extension.decode("utf-8")
            extension = self._safe(raw_extension).lower().lstrip(".")
            if extension not in OFFICE_EXTENSIONS:
                raise RuntimeError("unsupported document preview format")
            params = dict(self._params(kvargs))
            params["_previewRequestId"] = request_id
            _log("read_start", requestId=request_id, format=extension)
            helper = OfficeFileMetadataExtractor(data, args, params)
            helper.params["maxRawOfficeBytes"] = min(helper.limit("maxRawOfficeBytes"), 64 * 1024 * 1024)
            raw = helper.read_file_bytes()  # Same full-byte reader as working image/Office extraction.
            _log("read_done", requestId=request_id, bytes=len(raw))
            result = self._cached_render(raw, extension, params, helper)
        except Exception as exc:
            _failure("preview_failed", request_id, exc)
            result = dict(status="FAILED", message=str(exc))
        _log("preview_done", requestId=request_id, status=result.get("status"),
             message=result.get("message", ""), previewPages=result.get("previewPages", 0),
             elapsedMs=int((time.monotonic() - started) * 1000))
        return [["preview"], ["BINARY"], [json.dumps(result, ensure_ascii=False)]]

    @contextmanager
    def _lock(self, path, timeout):
        deadline = time.monotonic() + timeout
        owner = uuid.uuid4().hex
        waiting = False
        while True:
            try:
                fd = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                with os.fdopen(fd, "w") as stream:
                    stream.write(owner)
                break
            except FileExistsError:
                if not waiting:
                    _log("lock_wait", lock=path.name, timeoutSeconds=timeout)
                    waiting = True
                # Rendering runs in IGinX; age alone cannot prove the holder has stopped.
                if time.monotonic() >= deadline:
                    raise RuntimeError("document preview is busy; please retry")
                time.sleep(0.2)
        try:
            if waiting:
                _log("lock_acquired", lock=path.name)
            yield
        finally:
            try:
                if path.read_text() == owner:
                    path.unlink()
            except FileNotFoundError:
                pass

    def _cached_render(self, raw, extension, params, helper):
        root = Path(params.get("officePreviewCacheDir") or
                    str(Path(tempfile.gettempdir()) / "scalestore-document-preview"))
        root.mkdir(parents=True, exist_ok=True)
        # Hash the actual bytes: stale/estimated metadata sizes are not a content version.
        key = hashlib.sha256(raw + (RENDERER_VERSION + extension +
                             str(params.get("officeSofficePath", "soffice"))).encode()).hexdigest()
        cached = root / (key + ".json")
        ttl = helper.limit("officePreviewCacheTtlSeconds")
        timeout = helper.limit("officePreviewLockTimeoutSeconds")
        request_id = params.get("_previewRequestId", "")
        _log("cache_lookup", requestId=request_id, cacheKey=key[:12], directory=str(root))
        # Serialize access across IGinX interpreters using the same cache directory.
        with self._lock(root / (key + ".lock"), timeout):
            try:
                stat = cached.stat()
                if time.time() - stat.st_mtime < ttl and stat.st_size <= MAX_RESULT_BYTES:
                    result = json.loads(cached.read_text(encoding="utf-8"))
                    if result.get("status") == "READY":
                        _log("cache_hit", requestId=request_id, cacheKey=key[:12])
                        return result
            except (OSError, ValueError):
                pass  # Another renderer may prune this cached result while it is being read.
            # Serialize preview rendering per node/cache directory, including across interpreters.
            # LLM extraction is separate and is never called here.
            _log("cache_miss", requestId=request_id, cacheKey=key[:12])
            with self._lock(root / "render.lock", timeout):
                with tempfile.TemporaryDirectory(prefix="render-", dir=str(root)) as workspace:
                    work = Path(workspace)
                    source = work / ("source." + extension)
                    source.write_bytes(raw)
                    _log("source_saved", requestId=request_id, source=str(source), bytes=len(raw))
                    # IGinX invokes transform in its managed interpreter, like the extraction UDFs.
                    result = render(source, extension, params)
                encoded = json.dumps(result, ensure_ascii=False)
                if len(encoded.encode("utf-8")) > MAX_RESULT_BYTES:
                    raise RuntimeError("document preview result exceeds size limit")
                self._prune(root, helper.limit("officePreviewCacheMaxBytes"), ttl)
                temporary = root / (key + ".tmp")
                temporary.write_text(encoded, encoding="utf-8")
                os.replace(str(temporary), str(cached))
                _log("cache_saved", requestId=request_id, cacheKey=key[:12], bytes=cached.stat().st_size)
                self._prune(root, helper.limit("officePreviewCacheMaxBytes"), ttl)
                return result

    @staticmethod
    def _prune(root, max_bytes, ttl):
        files = []
        for path in root.glob("*.json"):
            if path.is_symlink() or not re.fullmatch(r"[0-9a-f]{64}\.json", path.name):
                continue
            try:
                stat = path.stat()
                if time.time() - stat.st_mtime >= ttl:
                    path.unlink()
                else:
                    files.append((stat.st_mtime, stat.st_size, path))
            except FileNotFoundError:
                pass
        total = sum(item[1] for item in files)
        for _, size, path in sorted(files):
            if total <= max_bytes:
                break
            try:
                path.unlink()
            except FileNotFoundError:
                pass
            total -= size


def render(source, extension, params):
    request_id = params.get("_previewRequestId", "")
    started = time.monotonic()
    _log("render_start", requestId=request_id, format=extension, source=str(source))
    helper = OfficeFileMetadataExtractor([], None, params)
    with source.open("rb") as stream:
        signature = stream.read(1024)
    if extension == "pdf":
        if b"%PDF-" not in signature:
            raise RuntimeError("PDF signature mismatch")
    else:
        if extension in ("doc", "ppt"):
            if not signature.startswith(bytes.fromhex("D0CF11E0A1B11AE1")):
                raise RuntimeError("legacy Office signature mismatch")
        else:
            helper._validate_package(source, extension)
        conversion_started = time.monotonic()
        _log("convert_start", requestId=request_id, format=extension,
             soffice=params.get("officeSofficePath", "soffice"))
        source = helper._convert(source, "pdf", source.parent)
        _log("convert_done", requestId=request_id, pdfBytes=source.stat().st_size,
             elapsedMs=int((time.monotonic() - conversion_started) * 1000))

    _log("pdf_open_start", requestId=request_id)
    import pypdfium2 as pdfium
    from PIL import Image
    pages = []
    # Called within the preview render lock in the IGinX-managed interpreter.
    document = pdfium.PdfDocument(str(source))
    try:
        total = len(document)
        _log("pdf_open_done", requestId=request_id, totalPages=total, previewPages=min(total, MAX_PAGES))
        if not total:
            raise RuntimeError("document contains no pages")
        for index in range(min(total, MAX_PAGES)):
            page_started = time.monotonic()
            _log("page_render_start", requestId=request_id, page=index + 1)
            page = document[index]
            try:
                width, height = page.get_size()
                if min(width, height) <= 0:
                    raise RuntimeError("invalid page dimensions")
                bitmap = page.render(scale=min(2.0, MAX_SIDE / max(width, height)))
                try:
                    picture = bitmap.to_pil().convert("RGB")
                    picture.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
                    output = io.BytesIO()
                    picture.save(output, format="JPEG", quality=85)
                    if output.tell() > MAX_PAGE_BYTES:
                        output = io.BytesIO()
                        picture.thumbnail((1200, 1200), Image.Resampling.LANCZOS)
                        picture.save(output, format="JPEG", quality=70)
                    if output.tell() > MAX_PAGE_BYTES:
                        raise RuntimeError("preview page exceeds size limit")
                    pages.append(dict(number=index + 1, width=picture.width, height=picture.height,
                                      image=base64.b64encode(output.getvalue()).decode("ascii")))
                    _log("page_render_done", requestId=request_id, page=index + 1,
                         width=picture.width, height=picture.height, bytes=output.tell(),
                         elapsedMs=int((time.monotonic() - page_started) * 1000))
                finally:
                    bitmap.close()
            finally:
                page.close()
    finally:
        document.close()
    _log("render_done", requestId=request_id, previewPages=len(pages),
         elapsedMs=int((time.monotonic() - started) * 1000))
    return dict(status="READY", totalPages=total, previewPages=len(pages), pages=pages)
