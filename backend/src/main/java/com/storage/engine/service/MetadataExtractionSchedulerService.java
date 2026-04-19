package com.storage.engine.service;

import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataExtractResult;
import com.storage.engine.model.Node;
import com.storage.engine.model.Policy;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);

    private static final int MAX_EVENT_CACHE = 300;
    private static final String MODE_WEBSERVER = "webserver";
    private static final int TRANSFORM_FETCH_LIMIT = 80;

    private static final long DEFAULT_SCAN_INTERVAL_MS = 60000L;
    private static final int DEFAULT_SCAN_BATCH_SIZE = 20;

    private volatile long nextScanTimestamp = 0L;
    private final Set<Long> seenTransformKeys = Collections.synchronizedSet(new HashSet<Long>());

    @Autowired
    private AccessService accessService;

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private MetadataUdfService metadataUdfService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private PolicyService policyService;

    @Value("${metadata.extraction.scheduler-mode:webserver}")
    private String schedulerMode;

    private final Set<Integer> processingIds = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        if (isWebserverMode()) {
            publishEvent("info", "初始化", "元数据定时抽取调度已启动，等待扫描任务。", "IGinX-Scheduler");
            return;
        }

        publishEvent("info", "初始化", "当前采用IGinX Transform定时任务模式，WebServer定时抽取已禁用。", "IGinX-Transform");
    }

    @Scheduled(fixedDelay = 2000)
    public void scanAndExtract() {
        if (!isWebserverMode()) {
            pollTransformAgentEvents();
            return;
        }

        Policy effectivePolicy = policyService.getPolicy();
        boolean enabled = effectivePolicy != null && Boolean.TRUE.equals(effectivePolicy.getExtractionEnabled());
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        long scanIntervalMs = DEFAULT_SCAN_INTERVAL_MS;
        if (effectivePolicy != null && effectivePolicy.getExtractionScanIntervalMs() != null) {
            scanIntervalMs = Math.max(1000L, effectivePolicy.getExtractionScanIntervalMs());
        }

        if (now < nextScanTimestamp) {
            return;
        }
        nextScanTimestamp = now + scanIntervalMs;

        List<DataItem> allMeta = accessService.getAllMeta();
        if (allMeta == null || allMeta.isEmpty()) {
            return;
        }

        List<DataItem> candidates = new ArrayList<DataItem>();
        for (DataItem item : allMeta) {
            if (item == null || item.getId() == null) {
                continue;
            }
            String status = normalizeStatus(item.getKnowledgeExtractStatus());
            if (isPendingLike(status)) {
                candidates.add(item);
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(new Comparator<DataItem>() {
            @Override
            public int compare(DataItem a, DataItem b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });

        int maxPerScan = DEFAULT_SCAN_BATCH_SIZE;
        if (effectivePolicy != null && effectivePolicy.getExtractionScanBatchSize() != null) {
            maxPerScan = Math.max(1, effectivePolicy.getExtractionScanBatchSize());
        }
        int count = 0;
        for (DataItem candidate : candidates) {
            if (count >= maxPerScan) {
                break;
            }
            if (!processingIds.add(candidate.getId())) {
                continue;
            }
            try {
                processOne(candidate);
            } finally {
                processingIds.remove(candidate.getId());
            }
            count++;
        }
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

    private void processOne(DataItem snapshot) {
        DataItem latest = accessService.getMetaByPathAndFileName(snapshot.getLogicalPath(), snapshot.getFileName());
        if (latest == null || latest.getId() == null) {
            return;
        }

        String latestStatus = normalizeStatus(latest.getKnowledgeExtractStatus());
        if (!isPendingLike(latestStatus)) {
            return;
        }

        long id = latest.getId().longValue();
        String logicalPath = safe(latest.getLogicalPath());
        String fileName = safe(latest.getFileName());
        String dataType = safe(latest.getDataType());
        String agentName = pickAgentName(latest);

        safeUpdateStatus(id, "PROCESSING");
        publishEvent(
                "running",
                "进行中",
            "正在执行UDF抽取，逻辑目录 " + logicalPath + "，文件 " + fileName + "，类型 " + dataType + "。",
                agentName);

        try {
            MetadataExtractResult result = metadataUdfService.extractByUdf(latest);
            logExtractResult(latest, result);
            safeUpdateStatus(id, "SUCCESS");
            publishEvent(
                    "success",
                    "完成",
                    buildSuccessText(logicalPath, fileName, dataType, result),
                    agentName);
        } catch (Exception e) {
            safeUpdateStatus(id, "FAILED");
                logger.error("元数据定时抽取失败: id={}, logicalPath={}, fileName={}, error={}",
                    id, logicalPath, fileName, e.getMessage(), e);
            publishEvent(
                    "warn",
                    "失败",
                    "UDF抽取失败，逻辑目录 " + logicalPath + "，文件 " + fileName + "，原因: " + safe(e.getMessage()),
                    agentName);
        }
    }

    private void safeUpdateStatus(long id, String status) {
        try {
            iginxDao.updateMetaKnowledgeStatus(id, status);
        } catch (Exception e) {
            logger.error("更新知识抽取状态失败: id={}, status={}, error={}", id, status, e.getMessage(), e);
        }
    }

    private String buildSuccessText(String logicalPath, String fileName, String dataType, MetadataExtractResult result) {
        String dt = safe(dataType).toLowerCase(Locale.ROOT);
        if ("relational".equals(dt) || "timeseries".equals(dt) || "keyvalue".equals(dt)) {
            int fieldCount = result == null || result.getFields() == null ? 0 : result.getFields().size();
            String text = "UDF抽取完成，逻辑目录 " + logicalPath + "，文件 " + fileName + "，field实体 " + fieldCount + " 个。";
            String udfMessage = result == null ? "" : sanitizeUdfMessage(result.getLlmResponse());
            if (!udfMessage.isEmpty()) {
                text = text + " " + udfMessage;
            }
            return text;
        }

        int entityCount = result == null || result.getEntities() == null ? 0 : result.getEntities().size();
        int tripleCount = result == null || result.getTriples() == null ? 0 : result.getTriples().size();
        String text = "UDF抽取完成，逻辑目录 " + logicalPath + "，文件 " + fileName + "，实体 " + entityCount + " 个，三元组 " + tripleCount + " 条。";
        String udfMessage = result == null ? "" : sanitizeUdfMessage(result.getLlmResponse());
        if (!udfMessage.isEmpty()) {
            text = text + " " + udfMessage;
        }
        return text;
    }

    private String sanitizeUdfMessage(String message) {
        String cleaned = safe(message)
                .replaceAll("(?i)[a-z]+(?:\\s+[a-z]+)*\\s+extraction by udf;\\s*neo4j persisted\\.?", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return cleaned;
    }

    private void logExtractResult(DataItem item, MetadataExtractResult result) {
        if (item == null) {
            return;
        }
        if (result == null) {
            logger.info("元数据抽取完成: logicalPath={}, dataType={}, result=empty",
                    safe(item.getLogicalPath()), safe(item.getDataType()));
            return;
        }

        logger.info("元数据抽取完成: logicalPath={}, dataType={}, fields={}, entities={}, triples={}",
                safe(item.getLogicalPath()),
                safe(item.getDataType()),
                result.getFields(),
                result.getEntities(),
                result.getTriples());

        String udfMessage = safe(result.getLlmResponse());
        if (!udfMessage.isEmpty()) {
            logger.info("UDF原始回答: logicalPath={}, raw={}",
                    safe(item.getLogicalPath()), udfMessage);
        }
    }

    private String pickAgentName(DataItem item) {
        try {
            List<Node> nodes = nodeService.getAllNodes();
            List<String> onlineNames = new ArrayList<String>();
            List<String> allNames = new ArrayList<String>();

            for (Node node : nodes) {
                if (node == null) {
                    continue;
                }
                String name = safe(node.getName());
                if (name.isEmpty()) {
                    continue;
                }
                allNames.add(name);
                if ("ONLINE".equalsIgnoreCase(safe(node.getStatus()))) {
                    onlineNames.add(name);
                }
            }

            List<String> selected = onlineNames.isEmpty() ? allNames : onlineNames;
            if (selected.isEmpty()) {
                return "IGinX-UDF";
            }

            int seed = item != null && item.getId() != null ? item.getId().intValue() : 0;
            int idx = Math.abs(seed) % selected.size();
            return selected.get(idx);
        } catch (Exception e) {
            logger.warn("获取节点名称失败，使用默认智能体名称: {}", e.getMessage());
            return "IGinX-UDF";
        }
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

    private boolean isPendingLike(String status) {
        if (status.isEmpty()) {
            return true;
        }
        return "PENDING".equals(status) || "FAILED".equals(status);
    }

    private String normalizeStatus(String status) {
        return safe(status).toUpperCase(Locale.ROOT);
    }

    private String normalizeLevel(String level) {
        String l = safe(level).toLowerCase(Locale.ROOT);
        if ("running".equals(l) || "success".equals(l) || "warn".equals(l)) {
            return l;
        }
        return "info";
    }

    private boolean isWebserverMode() {
        return MODE_WEBSERVER.equalsIgnoreCase(safe(schedulerMode));
    }

    private void pollTransformAgentEvents() {
        try {
            SessionExecuteSqlResult result = iginxDao.getTransformMetaExtractRows(TRANSFORM_FETCH_LIMIT);
            if (result == null || result.getValues() == null || result.getValues().isEmpty()) {
                return;
            }

            List<String> paths = result.getPaths() == null ? new ArrayList<String>() : result.getPaths();
            int keyIdx = findColumnIndex(paths, "key");
            int statusIdx = findColumnIndex(paths, "transform.metaExtract.status");
            int messageIdx = findColumnIndex(paths, "transform.metaExtract.message");
            int errorIdx = findColumnIndex(paths, "transform.metaExtract.error");
            int entityCountIdx = findColumnIndex(paths, "transform.metaExtract.entityCount");
            int relationCountIdx = findColumnIndex(paths, "transform.metaExtract.relationCount");
                int detailsJsonIdx = findColumnIndex(paths, "transform.metaExtract.detailsJson");
            int logicalPathIdx = findColumnIndex(paths, "transform.metaExtract.logicalPath");
            int fileNameIdx = findColumnIndex(paths, "transform.metaExtract.fileName");
                int metaKeyIdx = findAnyColumnIndex(paths,
                    "transform.metaExtract.metaKey",
                    "transform.metaExtract.key");

            if (keyIdx < 0 || statusIdx < 0) {
                return;
            }

            Map<Long, DataItem> metaById = null;
            List<TransformMetaRow> items = new ArrayList<TransformMetaRow>();
            List<List<Object>> rows = result.getValues();
            for (int i = 0; i < rows.size(); i++) {
                List<Object> row = rows.get(i);
                if (row == null || keyIdx >= row.size()) {
                    continue;
                }

                Long transformKey = asLong(row.get(keyIdx));
                if (transformKey == null) {
                    continue;
                }

                if (!seenTransformKeys.add(transformKey)) {
                    continue;
                }

                String status = asString(row, statusIdx).toUpperCase(Locale.ROOT);
                String message = asString(row, messageIdx);
                String error = asString(row, errorIdx);
                Long entityCount = asLong(row, entityCountIdx);
                Long relationCount = asLong(row, relationCountIdx);
                String detailsJson = asString(row, detailsJsonIdx);
                String logicalPath = asString(row, logicalPathIdx);
                String fileName = asString(row, fileNameIdx);
                Long metaKey = asLong(row, metaKeyIdx);
                Long fieldCount = extractCountFromDetails(detailsJson, "fieldCount");

                Long entityFromDetails = extractCountFromDetails(detailsJson, "entityCount");
                if ((entityCount == null || entityCount.longValue() <= 0L) && entityFromDetails != null) {
                    entityCount = entityFromDetails;
                }

                Long relationFromDetails = extractCountFromDetails(detailsJson, "relationCount");
                if ((relationCount == null || relationCount.longValue() <= 0L) && relationFromDetails != null) {
                    relationCount = relationFromDetails;
                }

                if ((safe(logicalPath).isEmpty() || safe(fileName).isEmpty()) && metaKey != null) {
                    if (metaById == null) {
                        metaById = buildMetaByIdIndex();
                    }
                    DataItem meta = metaById.get(metaKey);
                    if (meta != null) {
                        if (safe(logicalPath).isEmpty()) {
                            logicalPath = safe(meta.getLogicalPath());
                        }
                        if (safe(fileName).isEmpty()) {
                            fileName = safe(meta.getFileName());
                        }
                    }
                }

                items.add(new TransformMetaRow(
                    transformKey,
                    status,
                    message,
                    error,
                    entityCount,
                    relationCount,
                    fieldCount,
                    logicalPath,
                    fileName));
            }

            Collections.sort(items, new Comparator<TransformMetaRow>() {
                @Override
                public int compare(TransformMetaRow a, TransformMetaRow b) {
                    return Long.compare(a.key, b.key);
                }
            });

            for (TransformMetaRow item : items) {
                if ("SUCCESS".equals(item.status)) {
                    String text = buildTransformSuccessText(
                            item.logicalPath,
                            item.fileName,
                            item.entityCount,
                            item.relationCount,
                            item.fieldCount,
                            item.message);
                    publishEvent("success", "完成", text, "IGinX-Transform");
                } else {
                    String text = buildTransformFailText(item);
                    publishEvent("warn", "失败", text, "IGinX-Transform");
                }
            }
        } catch (Exception e) {
            logger.debug("pollTransformAgentEvents skipped: {}", e.getMessage());
        }
    }

    private String buildTransformSuccessText(
            String logicalPath,
            String fileName,
            Long entityCount,
            Long relationCount,
            Long fieldCount,
            String message) {
        long safeEntityCount = entityCount == null ? 0L : entityCount.longValue();
        long safeRelationCount = relationCount == null ? 0L : relationCount.longValue();
        long safeFieldCount = fieldCount == null ? 0L : fieldCount.longValue();

        String pathPart = safe(logicalPath).isEmpty() ? "(unknown)" : safe(logicalPath);
        String filePart = safe(fileName).isEmpty() ? "(unknown)" : safe(fileName);
        String text;
        if (safeEntityCount <= 0L && safeRelationCount <= 0L && safeFieldCount > 0L) {
            text = "Transform抽取完成，逻辑目录 " + pathPart + "，文件名 " + filePart
                    + "，字段实体 " + safeFieldCount + " 个。";
        } else {
            text = "Transform抽取完成，逻辑目录 " + pathPart + "，文件名 " + filePart
                    + "，实体 " + safeEntityCount + " 个，三元组 " + safeRelationCount + " 条。";
        }

        String clean = sanitizeUdfMessage(message);
        if (!clean.isEmpty()) {
            text = text + " " + clean;
        }
        return text;
    }

    private String buildTransformFailText(TransformMetaRow item) {
        String pathPart = safe(item.logicalPath).isEmpty() ? "(unknown)" : safe(item.logicalPath);
        String filePart = safe(item.fileName).isEmpty() ? "(unknown)" : safe(item.fileName);
        String reason = item.error.isEmpty() ? item.message : item.error;
        return "Transform抽取失败，逻辑目录 " + pathPart + "，文件名 " + filePart + "，原因: " + safe(reason);
    }

    private int findAnyColumnIndex(List<String> paths, String... tails) {
        if (tails == null) {
            return -1;
        }
        for (String tail : tails) {
            int idx = findColumnIndex(paths, tail);
            if (idx >= 0) {
                return idx;
            }
        }
        return -1;
    }

    private int findColumnIndex(List<String> paths, String tail) {
        if (paths == null || tail == null) {
            return -1;
        }
        String normalizedTail = tail.toLowerCase(Locale.ROOT);
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            if (p == null) {
                continue;
            }
            String normalizedPath = p.trim().toLowerCase(Locale.ROOT);
            if (normalizedPath.equals(normalizedTail) || normalizedPath.endsWith(normalizedTail)) {
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
            return new String((byte[]) value).trim();
        }
        return String.valueOf(value).trim();
    }

    private Long asLong(List<Object> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size()) {
            return null;
        }
        return asLong(row.get(idx));
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof byte[]) {
            try {
                return Long.parseLong(new String((byte[]) value).trim());
            } catch (Exception ignore) {
                return null;
            }
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractCountFromDetails(String detailsJson, String countName) {
        String raw = safe(detailsJson);
        if (raw.isEmpty() || safe(countName).isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);

            JsonNode counts = root.get("counts");
            if (counts != null) {
                JsonNode direct = counts.get(countName);
                if (direct != null && direct.isNumber()) {
                    return Long.valueOf(direct.longValue());
                }
            }

            JsonNode fallback = root.get(countName);
            if (fallback != null && fallback.isNumber()) {
                return Long.valueOf(fallback.longValue());
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    private Map<Long, DataItem> buildMetaByIdIndex() {
        Map<Long, DataItem> out = new LinkedHashMap<Long, DataItem>();
        try {
            List<DataItem> all = accessService.getAllMeta();
            if (all == null) {
                return out;
            }
            for (DataItem item : all) {
                if (item == null || item.getId() == null) {
                    continue;
                }
                out.put(Long.valueOf(item.getId().longValue()), item);
            }
        } catch (Exception e) {
            logger.debug("buildMetaByIdIndex skipped: {}", e.getMessage());
        }
        return out;
    }

    private static class TransformMetaRow {
        private final long key;
        private final String status;
        private final String message;
        private final String error;
        private final Long entityCount;
        private final Long relationCount;
        private final Long fieldCount;
        private final String logicalPath;
        private final String fileName;

        private TransformMetaRow(
                long key,
                String status,
                String message,
                String error,
                Long entityCount,
                Long relationCount,
                Long fieldCount,
                String logicalPath,
                String fileName) {
            this.key = key;
            this.status = status;
            this.message = message;
            this.error = error;
            this.entityCount = entityCount;
            this.relationCount = relationCount;
            this.fieldCount = fieldCount;
            this.logicalPath = logicalPath;
            this.fileName = fileName;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
