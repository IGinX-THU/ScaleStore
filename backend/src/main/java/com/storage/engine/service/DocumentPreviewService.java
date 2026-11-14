package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.DataItem;
import com.storage.engine.service.adapter.StorageUtils;
import com.storage.engine.utils.FileSemanticSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Web-side orchestration/cache only. All file parsing and rendering runs in the IGinX UDSF. */
@Service
public class DocumentPreviewService {
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final long MAX_CACHE_BYTES = 64 * 1024 * 1024L;
    private static final long MAX_FILE_BYTES = 64 * 1024 * 1024L;
    private static final long GENERATION_TIMEOUT_MS = 180000L;
    @Autowired private AccessService accessService;
    @Autowired private IGinxDao iginxDao;
    @Autowired private ObjectMapper objectMapper;
    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "document-preview-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), runnable -> {
                Thread thread = new Thread(runnable, "document-preview");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public DocumentPreviewService() { deadlines.setRemoveOnCancelPolicy(true); }

    public Map<String, Object> request(long metaKey, boolean refresh) {
        DataItem meta = requireOffice(metaKey);
        String path = accessService.resolveIginxDataPath(meta);
        String format = FileSemanticSupport.extension(meta.getFileFormat(), meta.getFileName());
        String fingerprint = path + "|" + format + "|" + meta.getFileSize() + "|" + meta.getCreateTime();
        String sql = buildSql(path, format);
        synchronized (jobs) {
            prune();
            Job current = jobs.get(metaKey);
            if (current != null && current.fingerprint.equals(fingerprint)
                    && ("GENERATING".equals(current.status) || (!refresh && current.expires > System.currentTimeMillis()))) {
                return describe(metaKey, current);
            }
            Job job = new Job(fingerprint);
            jobs.put(metaKey, job);
            try {
                job.task = executor.submit(() -> generate(job, sql));
                job.alarm = deadlines.schedule(() -> expire(job), GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exc) {
                if (job.task != null) {
                    job.task.cancel(true);
                    executor.remove((Runnable) job.task);
                }
                job.message = "预览任务较多，请稍后重试";
                job.expires = System.currentTimeMillis() + 10000;
                job.status = "FAILED";
            }
            return describe(metaKey, job);
        }
    }

    private DataItem requireOffice(long metaKey) {
        DataItem meta = accessService.getMetaByKey(metaKey);
        if (meta == null || Boolean.FALSE.equals(meta.getIsValid())) {
            synchronized (jobs) { jobs.remove(metaKey); }
            throw new IllegalArgumentException("文件不存在或已失效");
        }
        if (!FileSemanticSupport.isOffice(meta.getDataType(), meta.getFileFormat(), meta.getFileName())) {
            throw new IllegalArgumentException("当前仅支持 DOC、DOCX、PDF、PPT、PPTX 预览");
        }
        if (meta.getFileSize() != null && meta.getFileSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 64 MiB 预览上限，请下载原文件");
        }
        return meta;
    }

    public byte[] page(long metaKey, String token, int page) {
        DataItem meta = requireOffice(metaKey);
        String fingerprint = accessService.resolveIginxDataPath(meta) + "|"
                + FileSemanticSupport.extension(meta.getFileFormat(), meta.getFileName()) + "|"
                + meta.getFileSize() + "|" + meta.getCreateTime();
        synchronized (jobs) {
            Job job = jobs.get(metaKey);
            if (job == null || !job.token.equals(token) || !"READY".equals(job.status)
                    || !job.fingerprint.equals(fingerprint)
                    || job.expires < System.currentTimeMillis() || page < 1 || page > job.images.size()) {
                throw new IllegalArgumentException("预览已过期或页码无效，请刷新预览");
            }
            return job.images.get(page - 1);
        }
    }

    static String buildSql(String path, String format) {
        if (!FileSemanticSupport.isOffice("file", format, "")) throw new IllegalArgumentException("unsupported format");
        String[] pair = StorageUtils.splitParentAndLeaf(path);
        if (pair[0].isEmpty() || pair[1].isEmpty()) throw new IllegalArgumentException("invalid file path");
        // A concrete leaf (not storage.meta.*) selects exactly the file content column.
        // PyUDSF/DataUtils supply all rows, headers and types; no byte truncation/LIMIT.
        return "SELECT office_file_preview(" + StorageUtils.quoteIdentifierSegment(
                StorageUtils.normalizeEscapedPath(pair[1])) + ", '" + format + "') FROM "
                + StorageUtils.quoteIdentifierPath(pair[0]) + ";";
    }

    private void generate(Job job, String sql) {
        try {
            long remaining = TimeUnit.NANOSECONDS.toMillis(job.deadline - System.nanoTime());
            if (remaining <= 0 || !"GENERATING".equals(job.status)) {
                expire(job);
                return;
            }
            SessionExecuteSqlResult result = iginxDao.executeLongRunningSql(sql);
            if (result == null || result.getValues() == null || result.getValues().size() != 1
                    || result.getValues().get(0).size() != 1) {
                throw new IllegalStateException("预览 UDF 未返回有效结果，请确认 office_file_preview 已注册且文件存在");
            }
            Object value = result.getValues().get(0).get(0);
            String payload = value instanceof byte[] ? new String((byte[]) value, StandardCharsets.UTF_8) : String.valueOf(value);
            if (payload.length() > 9 * 1024 * 1024) throw new IllegalStateException("预览结果过大");
            JsonNode node = objectMapper.readTree(payload);
            if (!"READY".equals(node.path("status").asText())) {
                throw new IllegalStateException(node.path("message").asText("文档预览生成失败"));
            }
            JsonNode pages = node.path("pages");
            int total = node.path("totalPages").asInt();
            if (!pages.isArray() || pages.size() < 1 || pages.size() > 3 || total < pages.size()) {
                throw new IllegalStateException("预览页数无效");
            }
            List<byte[]> images = new ArrayList<>();
            List<Map<String, Object>> dimensions = new ArrayList<>();
            for (JsonNode page : pages) {
                byte[] bytes = Base64.getDecoder().decode(page.path("image").asText());
                int width = page.path("width").asInt(), height = page.path("height").asInt();
                if (bytes.length < 3 || bytes.length > 2 * 1024 * 1024 || (bytes[0] & 255) != 255
                        || (bytes[1] & 255) != 216 || width < 1 || height < 1 || width > 1600 || height > 1600) {
                    throw new IllegalStateException("预览图片无效");
                }
                images.add(bytes);
                Map<String, Object> dimension = new LinkedHashMap<>();
                dimension.put("width", width);
                dimension.put("height", height);
                dimensions.add(dimension);
            }
            synchronized (jobs) {
                if (!"GENERATING".equals(job.status) || System.nanoTime() >= job.deadline) {
                    expire(job);
                    return;
                }
                job.images = images;
                job.dimensions = dimensions;
                job.total = total;
                job.expires = System.currentTimeMillis() + TTL_MS;
                job.status = "READY";
                prune();
            }
        } catch (Exception exc) {
            synchronized (jobs) {
                if ("GENERATING".equals(job.status)) {
                    job.message = exc.getMessage() == null ? "文档预览生成失败" : exc.getMessage();
                    job.expires = System.currentTimeMillis() + 30000;
                    job.status = "FAILED";
                }
            }
        } finally {
            if (job.alarm != null) job.alarm.cancel(false);
        }
    }

    private void expire(Job job) {
        synchronized (jobs) {
            if (!"GENERATING".equals(job.status)) return;
            job.message = "预览生成超时，请稍后重试";
            job.expires = System.currentTimeMillis() + 30000;
            job.status = "FAILED";
            if (job.task != null) {
                job.task.cancel(true);
                executor.remove((Runnable) job.task);
            }
        }
    }

    private Map<String, Object> describe(long metaKey, Job job) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", job.status);
        state.put("message", job.message);
        state.put("maxPages", 3);
        if ("READY".equals(job.status)) {
            state.put("totalPages", job.total);
            List<Map<String, Object>> pages = new ArrayList<>();
            for (int index = 0; index < job.images.size(); index++) {
                Map<String, Object> page = new LinkedHashMap<>(job.dimensions.get(index));
                page.put("number", index + 1);
                page.put("url", "/access/document-preview/" + metaKey + "/pages/" + (index + 1) + "?token=" + job.token);
                pages.add(page);
            }
            state.put("pages", pages);
            state.put("previewPages", pages.size());
        }
        return state;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        jobs.values().removeIf(job -> !"GENERATING".equals(job.status) && job.expires < now);
        long bytes = 0;
        for (Job job : jobs.values()) for (byte[] image : job.images) bytes += image.length;
        Iterator<Job> iterator = jobs.values().iterator();
        while ((jobs.size() > 24 || bytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
            Job job = iterator.next();
            if ("GENERATING".equals(job.status)) continue;
            for (byte[] image : job.images) bytes -= image.length;
            iterator.remove();
        }
    }

    @PreDestroy public void close() {
        deadlines.shutdownNow();
        executor.shutdownNow();
    }

    private static final class Job {
        final String token = UUID.randomUUID().toString();
        final String fingerprint;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GENERATION_TIMEOUT_MS);
        Future<?> task;
        volatile ScheduledFuture<?> alarm;
        volatile String status = "GENERATING";
        volatile String message = "";
        volatile long expires = Long.MAX_VALUE;
        List<byte[]> images = Collections.emptyList();
        List<Map<String, Object>> dimensions = Collections.emptyList();
        int total;
        Job(String fingerprint) { this.fingerprint = fingerprint; }
    }
}
