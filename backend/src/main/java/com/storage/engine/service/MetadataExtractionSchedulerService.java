package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
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
import javax.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);
    private static final int MAX_EVENT_CACHE = 300;

    @Autowired
    private IGinxDao iginxDao;

    @Value("${metadata.extraction.enabled:true}")
    private boolean extractionEnabled;

    private final int treePersistenceConcurrency = 1;

    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);
    private final AtomicLong treeWorkerSeq = new AtomicLong(0L);
    private final Set<String> relationExpansionInFlight = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor metadataTreeExecutor;

    @PostConstruct
    public void init() {
        int workerCount = Math.max(1, treePersistenceConcurrency);
        metadataTreeExecutor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "metadata-tree-worker-" + treeWorkerSeq.incrementAndGet());
                        thread.setDaemon(false);
                        return thread;
                    }
                });
        String message = extractionEnabled
                ? "Metadata extraction transform jobs enabled."
                : "Metadata extraction transform jobs disabled.";
        publishEvent("info", "INIT", message, "Metadata-UDF");
    }

    @PreDestroy
    public void shutdown() {
        if (metadataTreeExecutor != null) {
            metadataTreeExecutor.shutdown();
        }
    }

    public void persistMetadataTreeAsync(DataItem item) {
        if (item == null || item.getId() == null) {
            return;
        }
        final String assetPath = assetPath(item);
        final String whereClause = buildTreePersistWhereClause(item);
        CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String sql = "select metadata_tree_persist(*) from " + IGinxConstants.STORAGE_META_PATH
                            + " where " + whereClause + ";";
                    logger.info("[Metadata-UDF][TREE-SQL] path={}, sql={}", assetPath, sql);
                    iginxDao.executeLongRunningSql(sql);
                    publishEvent("success", "TREE_DONE", "Metadata directory tree persisted to Neo4j: path=" + assetPath, "Metadata-UDF");
                } catch (Exception e) {
                    logger.warn("Persist metadata tree failed: {}", e.getMessage());
                    publishEvent("warn", "TREE_FAILED", "Metadata directory tree persist failed: path=" + assetPath + ", reason=" + safe(e.getMessage()), "Metadata-UDF");
                }
            }
        }, metadataTreeExecutor);
    }

    public void persistMetadataTreeForSourceAsync(String sourcePath, List<DataItem> importedItems) {
        final String whereClause = buildTreePersistWhereClause(importedItems);
        if (whereClause.isEmpty()) {
            return;
        }
        final String normalizedSourcePath = normalizePath(sourcePath);
        final int assetCount = countPersistableItems(importedItems);
        logger.info("[Metadata-UDF][TREE-SOURCE-QUEUED] sourcePath={}, assets={}, queued={}",
                normalizedSourcePath,
                assetCount,
                metadataTreeExecutor.getQueue().size());
        CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String sql = "select metadata_tree_persist(*) from " + IGinxConstants.STORAGE_META_PATH
                            + " where " + whereClause + ";";
                    logger.info("[Metadata-UDF][TREE-SOURCE-SQL] sourcePath={}, assets={}, sql={}",
                            normalizedSourcePath,
                            assetCount,
                            sql);
                    iginxDao.executeLongRunningSql(sql);
                    publishEvent("success", "TREE_SOURCE_DONE",
                            "Metadata directory tree persisted to Neo4j: sourcePath=" + normalizedSourcePath
                                    + ", assets=" + assetCount,
                            "Metadata-UDF");
                } catch (Exception e) {
                    logger.warn("Persist metadata tree failed: sourcePath={}, reason={}",
                            normalizedSourcePath,
                            e.getMessage());
                    publishEvent("warn", "TREE_SOURCE_FAILED",
                            "Metadata directory tree persist failed: sourcePath=" + normalizedSourcePath
                                    + ", reason=" + safe(e.getMessage()),
                            "Metadata-UDF");
                }
            }
        }, metadataTreeExecutor);
    }

    public void expandEntityRelationsAsync(String focusEntity, List<Long> metaKeys) {
        final String focus = safe(focusEntity);
        final String whereClause = buildMetaKeyWhereClause(metaKeys);
        if (focus.isEmpty() || whereClause.isEmpty() || !relationExpansionInFlight.add(focus)) {
            return;
        }
        CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String sql = "select metadata_semantic_entity_relation_expand(*, '"
                            + escapeSql(focus) + "') from " + IGinxConstants.STORAGE_META_PATH
                            + " where " + whereClause + ";";
                    logger.info("[Metadata-UDF][ENTITY-RELATION-SQL] focus={}, sql={}", focus, sql);
                    publishEvent("running", "ENTITY_RELATION_BATCH",
                            "Entity relation expansion started: focus=" + focus + ", candidates=" + metaKeys.size(),
                            "Metadata-UDF");
                    SessionExecuteSqlResult result = iginxDao.executeLongRunningSql(sql);
                    publishEvent("success", "ENTITY_RELATION_DONE",
                            "Entity relation expansion completed: focus=" + focus + ", "
                                    + extractEntityRelationSummary(result),
                            "Metadata-UDF");
                } catch (Exception e) {
                    logger.warn("Expand entity relations failed: focus={}, reason={}", focus, e.getMessage());
                    publishEvent("warn", "ENTITY_RELATION_FAILED",
                            "Entity relation expansion failed: focus=" + focus + ", reason=" + safe(e.getMessage()),
                            "Metadata-UDF");
                } finally {
                    relationExpansionInFlight.remove(focus);
                }
            }
        });
    }

    private String buildMetaKeyWhereClause(List<Long> metaKeys) {
        if (metaKeys == null || metaKeys.isEmpty()) {
            return "";
        }
        StringBuilder where = new StringBuilder();
        for (Long metaKey : metaKeys) {
            if (metaKey == null || metaKey.longValue() <= 0L) {
                continue;
            }
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("key = ").append(metaKey.longValue());
        }
        return where.toString();
    }

    private String extractEntityRelationSummary(SessionExecuteSqlResult result) {
        if (result == null || result.getValues() == null) {
            return "entity relation results: unavailable";
        }
        for (List<Object> row : result.getValues()) {
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                String text = decodeSqlValue(value);
                int index = text.indexOf("entity relation results:");
                if (index >= 0) {
                    return text.substring(index).trim();
                }
            }
        }
        return "entity relation results: unavailable";
    }

    private String decodeSqlValue(Object value) {
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        if (value instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) value).duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private String buildTreePersistWhereClause(DataItem item) {
        StringBuilder where = new StringBuilder();
        where.append("key = ").append(item.getId().longValue());

        String directoryRoot = isDirectory(item) ? assetPath(item) : normalizePath(item.getLogicalPath());
        List<String> directories = buildDirectoryAssetPaths(directoryRoot);
        for (String dirPath : directories) {
            String parentPath = parentPath(dirPath);
            String dirName = leafName(dirPath);
            where.append(" OR (dataType = '")
                    .append(IGinxConstants.TYPE_DIRECTORY)
                    .append("' AND logicalPath = '")
                    .append(escapeSql(parentPath))
                    .append("' AND fileName = '")
                    .append(escapeSql(dirName))
                    .append("')");
        }
        return where.toString();
    }

    private String buildTreePersistWhereClause(List<DataItem> items) {
        LinkedHashSet<Long> metaKeys = new LinkedHashSet<Long>();
        LinkedHashSet<String> directoryClauses = new LinkedHashSet<String>();
        if (items != null) {
            for (DataItem item : items) {
                if (item == null || item.getId() == null) {
                    continue;
                }
                metaKeys.add(item.getId().longValue());

                String directoryRoot = isDirectory(item) ? assetPath(item) : normalizePath(item.getLogicalPath());
                for (String directoryPath : buildDirectoryAssetPaths(directoryRoot)) {
                    String parentPath = parentPath(directoryPath);
                    String directoryName = leafName(directoryPath);
                    directoryClauses.add("(dataType = '" + IGinxConstants.TYPE_DIRECTORY
                            + "' AND logicalPath = '" + escapeSql(parentPath)
                            + "' AND fileName = '" + escapeSql(directoryName) + "')");
                }
            }
        }

        StringBuilder where = new StringBuilder();
        for (Long metaKey : metaKeys) {
            appendOrClause(where, "key = " + metaKey.longValue());
        }
        for (String directoryClause : directoryClauses) {
            appendOrClause(where, directoryClause);
        }
        return where.toString();
    }

    private void appendOrClause(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" OR ");
        }
        where.append(clause);
    }

    private int countPersistableItems(List<DataItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        LinkedHashSet<Long> metaKeys = new LinkedHashSet<Long>();
        for (DataItem item : items) {
            if (item != null && item.getId() != null) {
                metaKeys.add(item.getId().longValue());
            }
        }
        return metaKeys.size();
    }

    private List<String> buildDirectoryAssetPaths(String assetPath) {
        List<String> out = new ArrayList<String>();
        String normalized = normalizePath(assetPath);
        if ("/".equals(normalized)) {
            return out;
        }
        String[] parts = normalized.substring(1).split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String seg = safe(part);
            if (seg.isEmpty()) {
                continue;
            }
            current.append('/').append(seg);
            out.add(current.toString());
        }
        return out;
    }

    private String parentPath(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return normalized.substring(0, idx);
    }

    private String leafName(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private String escapeSql(String value) {
        return safe(value).replace("\\", "\\\\").replace("'", "''");
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

    public void handleLeafSemanticStartCallback(MetadataSemanticLeafCallbackRequest request) {
        handleSemanticStartCallback("Leaf", request);
    }

    public void handleDirectorySemanticCallback(MetadataSemanticLeafCallbackRequest request) {
        handleSemanticCallback("Directory", request);
    }

    public void handleDirectorySemanticStartCallback(MetadataSemanticLeafCallbackRequest request) {
        handleSemanticStartCallback("Directory", request);
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

    private void handleSemanticStartCallback(String assetKind, MetadataSemanticLeafCallbackRequest request) {
        if (request == null || request.getMetaKey() == null) {
            throw new IllegalArgumentException("metaKey is required");
        }

        long metaKey = request.getMetaKey().longValue();
        iginxDao.updateMetaKnowledgeStatus(metaKey, "PROCESSING");
        String assetPath = safe(request.getAssetPath());
        if (assetPath.isEmpty()) {
            assetPath = buildAssetPath(request.getLogicalPath(), request.getFileName());
        }
        String dataType = safe(request.getDataType()).toLowerCase(Locale.ROOT);
        String fileName = safe(request.getFileName());
        String title = assetPath.isEmpty() ? String.valueOf(metaKey) : assetPath;

        publishEvent(
                "running",
                "STARTED",
                assetKind + " semantic extraction started: path=" + title
                        + ", dataType=" + dataType
                        + ", fileName=" + fileName,
                "Metadata-UDF");
        logger.info(
                "[MetadataSemanticStartCallback] kind={}, metaKey={}, dataType={}, fileName={}, assetPath={}",
                assetKind.toLowerCase(Locale.ROOT),
                metaKey,
                dataType,
                fileName,
                assetPath);
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
