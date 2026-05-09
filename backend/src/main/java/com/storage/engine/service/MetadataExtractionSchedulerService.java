package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);

    private static final int MAX_EVENT_CACHE = 300;
    private static final int TRANSFORM_FETCH_LIMIT = 80;

    private final Set<Long> seenTransformKeys = Collections.synchronizedSet(new HashSet<Long>());

    @Autowired
    private AccessService accessService;

    @Autowired
    private IGinxDao iginxDao;

    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        publishEvent("info", "INIT", "Transform mode is enabled. Extraction event polling started.", "IGinX-Transform");
    }

    @Scheduled(fixedDelay = 2000)
    public void scanAndExtract() {
        pollTransformAgentEvents();
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
            for (List<Object> row : rows) {
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
                    publishEvent("success", "DONE", text, "IGinX-Transform");
                } else {
                    String text = buildTransformFailText(item);
                    publishEvent("warn", "FAILED", text, "IGinX-Transform");
                }
            }
        } catch (Exception e) {
            logger.debug("pollTransformAgentEvents skipped: {}", e.getMessage());
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

    private String normalizeLevel(String level) {
        String normalized = safe(level).toLowerCase(Locale.ROOT);
        if ("running".equals(normalized) || "success".equals(normalized) || "warn".equals(normalized)) {
            return normalized;
        }
        return "info";
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
            text = "Transform extraction completed: path=" + pathPart + ", file=" + filePart
                + ", fields=" + safeFieldCount + ".";
        } else {
            text = "Transform extraction completed: path=" + pathPart + ", file=" + filePart
                + ", entities=" + safeEntityCount + ", relations=" + safeRelationCount + ".";
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
        return "Transform extraction failed: path=" + pathPart + ", file=" + filePart + ", reason=" + safe(reason);
    }

    private String sanitizeUdfMessage(String message) {
        return safe(message)
                .replaceAll("(?i)[a-z]+(?:\\s+[a-z]+)*\\s+extraction by udf;\\s*neo4j persisted\\.?", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
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
            String path = paths.get(i);
            if (path == null) {
                continue;
            }
            String normalizedPath = path.trim().toLowerCase(Locale.ROOT);
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

    private String safe(String value) {
        return value == null ? "" : value.trim();
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
}
