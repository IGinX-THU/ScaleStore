package com.storage.engine.service;

import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataSemanticLeafCallbackRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);
    private static final int MAX_EVENT_CACHE = 300;

    @Autowired
    private IGinxDao iginxDao;

    @Value("${metadata.extraction.enabled:true}")
    private boolean extractionEnabled;

    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);
    @PostConstruct
    public void init() {
        String message = extractionEnabled
                ? "Metadata extraction transform jobs enabled."
                : "Metadata extraction transform jobs disabled.";
        publishEvent("info", "INIT", message, "Metadata-UDF");
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

    public void handleLeafSemanticCallback(MetadataSemanticLeafCallbackRequest request) {
        handleSemanticCallback("Leaf", request);
    }

    public void handleDirectorySemanticCallback(MetadataSemanticLeafCallbackRequest request) {
        handleSemanticCallback("Directory", request);
    }

    private void handleSemanticCallback(String assetKind, MetadataSemanticLeafCallbackRequest request) {
        if (request == null || request.getMetaKey() == null) {
            throw new IllegalArgumentException("metaKey is required");
        }

        long startedAt = System.currentTimeMillis();
        long metaKey = request.getMetaKey().longValue();
        String status = normalizeSemanticStatus(request.getStatus());
        String keywords = safe(request.getKeywords());
        if (keywords.isEmpty()) {
            keywords = "[]";
        }
        String message = safe(request.getMessage());
        String assetPath = safe(request.getAssetPath());
        if (assetPath.isEmpty()) {
            assetPath = buildAssetPath(request.getLogicalPath(), request.getFileName());
        }

        iginxDao.updateMetaSemanticKeywords(metaKey, status, keywords);

        String dataType = safe(request.getDataType()).toLowerCase(Locale.ROOT);
        String fileFormat = safe(request.getFileFormat()).toLowerCase(Locale.ROOT);
        String title = assetPath.isEmpty() ? String.valueOf(metaKey) : assetPath;
        if ("SUCCESS".equals(status)) {
            publishEvent(
                    "success",
                    assetKind.toUpperCase(Locale.ROOT) + "_SEMANTIC_SUCCESS",
                    assetKind + " semantic extraction completed: path=" + title
                            + ", dataType=" + dataType
                            + ", fileFormat=" + fileFormat
                            + ", keywords=" + keywords,
                    "Metadata-UDF");
        } else {
            publishEvent(
                    "warn",
                    assetKind.toUpperCase(Locale.ROOT) + "_SEMANTIC_FAILED",
                    assetKind + " semantic extraction failed: path=" + title
                            + ", dataType=" + dataType
                            + ", fileFormat=" + fileFormat
                            + ", reason=" + message,
                    "Metadata-UDF");
        }
        logger.info(
                "[MetadataSemanticCallback] persisted kind={}, metaKey={}, status={}, dataType={}, fileFormat={}, assetPath={}, keywordLength={}, elapsedMs={}",
                assetKind.toLowerCase(Locale.ROOT),
                metaKey,
                status,
                dataType,
                fileFormat,
                assetPath,
                keywords.length(),
                System.currentTimeMillis() - startedAt);
    }

    private boolean isDirectory(DataItem item) {
        return item != null && IGinxConstants.TYPE_DIRECTORY.equals(safe(item.getDataType()).toLowerCase(Locale.ROOT));
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

    private String normalizeSemanticStatus(String status) {
        String normalized = safe(status).toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(normalized)) {
            return "SUCCESS";
        }
        return "FAILED";
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

    private String buildAssetPath(String logicalPath, String fileName) {
        String normalizedPath = normalizePath(logicalPath);
        String safeFileName = safe(fileName);
        if (safeFileName.isEmpty()) {
            return normalizedPath;
        }
        if (normalizedPath.endsWith("/" + safeFileName)) {
            return normalizedPath;
        }
        return normalizePath(normalizedPath + "/" + safeFileName);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
