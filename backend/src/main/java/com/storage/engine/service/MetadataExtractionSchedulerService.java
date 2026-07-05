package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);
    private static final int MAX_EVENT_CACHE = 300;

    @Autowired
    private AccessService accessService;

    @Autowired
    private IGinxDao iginxDao;

    @Value("${metadata.extraction.enabled:true}")
    private boolean extractionEnabled;

    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);
    private final AtomicBoolean extractionRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        publishEvent("info", "INIT", "Metadata UDF extraction scheduler started.", "Metadata-UDF");
    }

    @Scheduled(fixedDelayString = "${metadata.extraction.scan-interval-ms:15000}")
    public void scanAndExtract() {
        if (!extractionEnabled || !extractionRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<DataItem> all = accessService.getAllMeta();
            DataItem candidate = selectCandidate(all);
            if (candidate == null || candidate.getId() == null) {
                return;
            }
            extractOne(candidate);
        } catch (Exception e) {
            logger.warn("Metadata UDF extraction scan failed: {}", e.getMessage());
            publishEvent("warn", "FAILED", "Metadata extraction scan failed: " + safe(e.getMessage()), "Metadata-UDF");
        } finally {
            extractionRunning.set(false);
        }
    }

    public void persistMetadataTreeAsync(DataItem item) {
        if (item == null || item.getId() == null || isDirectory(item)) {
            return;
        }
        final long key = item.getId().longValue();
        final String assetPath = assetPath(item);
        CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String sql = "select metadata_tree_persist(*) from " + IGinxConstants.STORAGE_META_PATH
                            + " where key = " + key + ";";
                    logger.info("[Metadata-UDF][TREE-SQL] path={}, sql={}", assetPath, sql);
                    iginxDao.executeLongRunningSql(sql);
                    publishEvent("success", "TREE_DONE", "Metadata directory tree persisted to Neo4j: path=" + assetPath, "Metadata-UDF");
                } catch (Exception e) {
                    logger.warn("Persist metadata tree failed: {}", e.getMessage());
                    publishEvent("warn", "TREE_FAILED", "Metadata directory tree persist failed: path=" + assetPath + ", reason=" + safe(e.getMessage()), "Metadata-UDF");
                }
            }
        });
    }

    public List<AgentMessageEvent> listEventsSince(long sinceSeq, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 120));
        List<AgentMessageEvent> out = new ArrayList<AgentMessageEvent>();

        synchronized (eventBuffer) {
            for (AgentMessageEvent event : eventBuffer) {
                if (event.getSeq() > sinceSeq) {
                    out.add(copyEvent(event));
                    if (out.size() > safeLimit) {
                        out.remove(0);
                    }
                }
            }
        }
        return out;
    }

    public long getLatestEventSeq() {
        return eventSeq.get();
    }

    public void publishExternalSourceEvent(String level, String status, String text, String agentName) {
        publishEvent(level, status, text, agentName);
    }

    private DataItem selectCandidate(List<DataItem> all) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        List<DataItem> sorted = new ArrayList<DataItem>(all);
        Collections.sort(sorted, new Comparator<DataItem>() {
            @Override
            public int compare(DataItem a, DataItem b) {
                int depthCompare = Integer.compare(assetDepth(assetPath(b)), assetDepth(assetPath(a)));
                if (depthCompare != 0) {
                    return depthCompare;
                }
                int aid = a.getId() == null ? Integer.MAX_VALUE : a.getId().intValue();
                int bid = b.getId() == null ? Integer.MAX_VALUE : b.getId().intValue();
                return Integer.compare(aid, bid);
            }
        });

        for (DataItem item : sorted) {
            if (item == null || item.getId() == null || !isDirectory(item)) {
                continue;
            }
            String status = status(item);
            boolean missingKeywords = safe(item.getSemanticKeywords()).isEmpty();
            if ((isPendingOrFailed(status) || ("SUCCESS".equals(status) && missingKeywords))
                    && directoryChildrenReady(item, all)) {
                return item;
            }
        }

        for (DataItem item : sorted) {
            if (item == null || item.getId() == null || isDirectory(item)) {
                continue;
            }
            if (isPendingOrFailed(status(item))) {
                return item;
            }
        }
        return null;
    }

    private void extractOne(DataItem item) {
        long key = item.getId().longValue();
        String assetPath = assetPath(item);
        String udfName = udfNameFor(item);
        if (udfName.isEmpty()) {
            iginxDao.updateMetaKnowledgeStatus(key, "FAILED");
            publishEvent("warn", "FAILED", "Unsupported metadata dataType: path=" + assetPath + ", dataType=" + safe(item.getDataType()), "Metadata-UDF");
            return;
        }

        publishEvent("running", "PROCESSING", "Metadata semantic extraction started: path=" + assetPath + ", udf=" + udfName, "Metadata-UDF");
        iginxDao.updateMetaKnowledgeStatus(key, "PROCESSING");

        try {
            String sql = buildUdfSql(udfName, item);
            logger.info("[Metadata-UDF][EXTRACT-SQL] path={}, udf={}, sql={}", assetPath, udfName, sql);
            SessionExecuteSqlResult result = iginxDao.executeLongRunningSql(sql);
            UdfResult udfResult = parseUdfResult(result);
            if (!"SUCCESS".equals(udfResult.status)) {
                iginxDao.updateMetaKnowledgeStatus(key, "FAILED");
                publishEvent("warn", "FAILED", "Metadata semantic extraction failed: path=" + assetPath + ", reason=" + udfResult.message, "Metadata-UDF");
                return;
            }

            iginxDao.updateMetaSemanticKeywords(key, "SUCCESS", udfResult.keywordsJson);
            publishEvent("success", "DONE", "Metadata semantic extraction completed: path=" + assetPath + ", keywords=" + udfResult.keywordsJson, "Metadata-UDF");
        } catch (Exception e) {
            iginxDao.updateMetaKnowledgeStatus(key, "FAILED");
            logger.warn("Metadata semantic extraction failed. path={}, error={}", assetPath, e.getMessage());
            publishEvent("warn", "FAILED", "Metadata semantic extraction failed: path=" + assetPath + ", reason=" + safe(e.getMessage()), "Metadata-UDF");
        }
    }

    private String buildUdfSql(String udfName, DataItem item) {
        String kvargs = "logicalPath='" + escapeSql(item.getLogicalPath()) + "'"
                + ", metaKey='" + item.getId().longValue() + "'"
                + ", fileName='" + escapeSql(item.getFileName()) + "'"
                + ", dataType='" + escapeSql(item.getDataType()) + "'"
                + ", fileFormat='" + escapeSql(item.getFileFormat()) + "'";
        if (isImageFile(item)) {
            kvargs = kvargs
                    + ", maxRawImageBytes='67108864'"
                    + ", maxVlmImageBytes='4194304'"
                    + ", maxVlmImageSide='1280'";
        }

        if (isDirectory(item)) {
            return "select " + udfName + "(*, " + kvargs + ") from " + IGinxConstants.STORAGE_META_PATH
                    + " where logicalPath = '" + escapeSql(assetPath(item)) + "';";
        }

        String contentPath = safe(item.getContentPath());
        if (contentPath.isEmpty()) {
            throw new IllegalArgumentException("leaf asset contentPath is empty");
        }
        return "select " + udfName + "(*, " + kvargs + ") from (SELECT VALUE2META(SELECT contentPath FROM "
                + IGinxConstants.STORAGE_META_PATH + " where key = " + item.getId().longValue() + ") from "
                + IGinxConstants.DATA_PATH_PREFIX + ");";
    }

    private UdfResult parseUdfResult(SessionExecuteSqlResult result) {
        UdfResult out = new UdfResult();
        out.status = "FAILED";
        out.keywordsJson = "[]";
        out.message = "empty udf result";
        if (isEmptyResult(result)) {
            return out;
        }

        List<String> paths = result.getPaths() == null ? new ArrayList<String>() : result.getPaths();
        int statusIdx = findColumnIndex(paths, "status");
        int keywordsIdx = findColumnIndex(paths, "keywords");
        int messageIdx = findColumnIndex(paths, "message");
        List<Object> row = findUdfPayloadRow(result.getValues());

        if (statusIdx < 0 && row.size() >= 1) {
            statusIdx = 0;
        }
        if (keywordsIdx < 0 && row.size() >= 2) {
            keywordsIdx = 1;
        }
        if (messageIdx < 0 && row.size() >= 5) {
            messageIdx = 4;
        }

        out.status = asString(row, statusIdx).toUpperCase(Locale.ROOT);
        if (out.status.isEmpty()) {
            out.status = "FAILED";
        }
        out.keywordsJson = asString(row, keywordsIdx);
        if (out.keywordsJson.isEmpty()) {
            out.keywordsJson = "[]";
        }
        out.message = asString(row, messageIdx);
        return out;
    }

    private boolean isEmptyResult(SessionExecuteSqlResult result) {
        return result == null || result.getValues() == null || result.getValues().isEmpty();
    }

    private List<Object> findUdfPayloadRow(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            List<Object> row = rows.get(i);
            if (row == null || row.isEmpty()) {
                continue;
            }
            String first = asString(row, 0);
            if ("BINARY".equalsIgnoreCase(first) || "status".equalsIgnoreCase(first)) {
                continue;
            }
            if ("SUCCESS".equalsIgnoreCase(first) || "FAILED".equalsIgnoreCase(first)) {
                return row;
            }
        }
        return rows.get(rows.size() - 1);
    }

    private boolean directoryChildrenReady(DataItem directory, List<DataItem> all) {
        String dirPath = assetPath(directory);
        boolean hasChild = false;
        for (DataItem item : all) {
            if (item == null || item == directory) {
                continue;
            }
            String childPath = assetPath(item);
            if (!dirPath.equals(parentPath(childPath))) {
                continue;
            }
            hasChild = true;
            if (!"SUCCESS".equals(status(item)) || safe(item.getSemanticKeywords()).isEmpty()) {
                return false;
            }
        }
        return hasChild;
    }

    private String udfNameFor(DataItem item) {
        String type = safe(item.getDataType()).toLowerCase(Locale.ROOT);
        switch (type) {
            case IGinxConstants.TYPE_DIRECTORY:
                return "directory_semantic_keywords";
            case IGinxConstants.TYPE_RELATIONAL:
                return "relational_semantic_keywords";
            case IGinxConstants.TYPE_TIMESERIES:
                return "timeseries_semantic_keywords";
            case IGinxConstants.TYPE_KEYVALUE:
                return "keyvalue_semantic_keywords";
            case IGinxConstants.TYPE_DOCUMENT:
                return "document_semantic_keywords";
            case IGinxConstants.TYPE_FILE:
                return "file_semantic_keywords";
        }
        return "";
    }

    private String assetPath(DataItem item) {
        if (item == null) {
            return "/";
        }
        String logicalPath = normalizePath(item.getLogicalPath());
        String fileName = safe(item.getFileName());
        if (fileName.isEmpty()) {
            return logicalPath;
        }
        if (logicalPath.endsWith("/" + fileName)) {
            return logicalPath;
        }
        return normalizePath(logicalPath + "/" + fileName);
    }

    private String parentPath(String path) {
        String p = normalizePath(path);
        if ("/".equals(p)) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return p.substring(0, idx);
    }

    private int assetDepth(String path) {
        String p = normalizePath(path);
        if ("/".equals(p)) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private boolean isDirectory(DataItem item) {
        return item != null && IGinxConstants.TYPE_DIRECTORY.equals(safe(item.getDataType()).toLowerCase(Locale.ROOT));
    }

    private boolean isImageFile(DataItem item) {
        String type = safe(item == null ? "" : item.getDataType()).toLowerCase(Locale.ROOT);
        String format = safe(item == null ? "" : item.getFileFormat()).toLowerCase(Locale.ROOT);
        return IGinxConstants.TYPE_FILE.equals(type)
                && ("jpg".equals(format)
                || "jpeg".equals(format)
                || "png".equals(format)
                || "bmp".equals(format)
                || "gif".equals(format)
                || "webp".equals(format));
    }

    private boolean isPendingOrFailed(String status) {
        return "PENDING".equals(status) || "FAILED".equals(status);
    }

    private String status(DataItem item) {
        String status = safe(item == null ? "" : item.getKnowledgeExtractStatus()).toUpperCase(Locale.ROOT);
        return status.isEmpty() ? "PENDING" : status;
    }

    private int findColumnIndex(List<String> paths, String tail) {
        if (paths == null || tail == null) {
            return -1;
        }
        String normalizedTail = tail.toLowerCase(Locale.ROOT);
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path == null) {
                continue;
            }
            String normalizedPath = path.trim().toLowerCase(Locale.ROOT).replace("(", "").replace(")", "");
            if (normalizedPath.equals(normalizedTail) || normalizedPath.endsWith("." + normalizedTail) || normalizedPath.endsWith(normalizedTail)) {
                return i;
            }
        }
        return -1;
    }

    private String asString(List<Object> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        Object value = row.get(idx);
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8).trim();
        }
        return String.valueOf(value).trim();
    }

    private void publishEvent(String level, String status, String text, String agentName) {
        AgentMessageEvent event = new AgentMessageEvent();
        event.setSeq(eventSeq.incrementAndGet());
        event.setTimestamp(System.currentTimeMillis());
        event.setLevel(normalizeLevel(level));
        event.setStatus(safe(status));
        event.setText(safe(text));
        event.setAgentName(safe(agentName));

        synchronized (eventBuffer) {
            eventBuffer.addLast(event);
            while (eventBuffer.size() > MAX_EVENT_CACHE) {
                eventBuffer.removeFirst();
            }
        }
    }

    private AgentMessageEvent copyEvent(AgentMessageEvent source) {
        AgentMessageEvent copy = new AgentMessageEvent();
        copy.setSeq(source.getSeq());
        copy.setTimestamp(source.getTimestamp());
        copy.setLevel(source.getLevel());
        copy.setStatus(source.getStatus());
        copy.setText(source.getText());
        copy.setAgentName(source.getAgentName());
        return copy;
    }

    private String normalizeLevel(String level) {
        String normalized = safe(level).toLowerCase(Locale.ROOT);
        if ("running".equals(normalized) || "success".equals(normalized) || "warn".equals(normalized)) {
            return normalized;
        }
        return "info";
    }

    private String normalizePath(String path) {
        String p = safe(path);
        if (p.isEmpty()) {
            return "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String escapeSql(String value) {
        return safe(value).replace("\\", "\\\\").replace("'", "''");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static class UdfResult {
        private String status;
        private String keywordsJson;
        private String message;
    }
}
