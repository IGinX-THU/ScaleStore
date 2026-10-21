package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.dao.Neo4jDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists immutable, versioned engineering drawing analysis documents in IGinX.
 * Neo4j remains a query projection; this document is the source of truth for the
 * BOM/structure tree, dimensions, text blocks and evidence coordinates.
 */
@Service
public class DrawingAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(DrawingAnalysisService.class);
    private static final AtomicLong ROW_KEY = new AtomicLong(System.currentTimeMillis() * 1000L);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() { };

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Neo4jDao neo4jDao;

    public synchronized Map<String, Object> persistExtraction(long sourceMetaKey, String rawPayload) {
        // storage.meta keys are allocated from zero, so zero is a valid first asset id.
        if (sourceMetaKey < 0L) {
            throw new IllegalArgumentException("sourceMetaKey must be non-negative");
        }
        Map<String, Object> payload = parseObject(rawPayload);
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("drawing analysis payload is empty");
        }

        int nextVersion = latestVersion(sourceMetaKey) + 1;
        String analysisId = "da-" + sourceMetaKey + "-v" + nextVersion + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String createdAt = Instant.now().toString();
        String status = initialStatus(payload);

        payload.put("schemaVersion", stringValue(payload.get("schemaVersion"), "1.0"));
        payload.put("analysisId", analysisId);
        payload.put("sourceMetaKey", Long.valueOf(sourceMetaKey));
        payload.put("analysisVersion", Integer.valueOf(nextVersion));
        payload.put("status", status);
        payload.put("createdAt", createdAt);
        payload.put("updatedAt", createdAt);

        Map<String, Object> summary = buildSummary(payload);
        insertRow(sourceMetaKey, nextVersion, analysisId, status, createdAt, payload, summary);
        // A failed projection must not invalidate an already persisted source document.
        try {
            neo4jDao.replaceDrawingProjection(DrawingGraphProjection.from(payload));
        } catch (RuntimeException e) {
            logger.warn("Drawing {} persisted; graph projection will retry on access: {}", analysisId, e.getMessage());
        }
        return payload;
    }

    /** Backfill existing analysis documents and retry failed projections without running recognition. */
    public synchronized boolean synchronizeProjections(List<Long> metaKeys) {
        if (!neo4jDao.isEnabled() || metaKeys == null || metaKeys.isEmpty()) return false;
        List<String> conditions = new ArrayList<>();
        for (Long key : metaKeys) if (key != null && key >= 0) conditions.add("sourceMetaKey = " + key);
        if (conditions.isEmpty()) return false;
        Map<Long, AnalysisRow> latest = new LinkedHashMap<>();
        for (AnalysisRow row : queryRows(String.join(" OR ", conditions))) {
            AnalysisRow previous = latest.get(row.sourceMetaKey);
            if (previous == null || row.version > previous.version) latest.put(row.sourceMetaKey, row);
        }
        if (latest.isEmpty()) return false;
        Set<String> projected = neo4jDao.currentDrawingAnalysisIds(new ArrayList<>(latest.keySet()));
        boolean changed = false;
        for (AnalysisRow row : latest.values()) {
            if (!projected.contains(safe(row.payload.get("analysisId")))) {
                changed |= neo4jDao.replaceDrawingProjection(DrawingGraphProjection.from(row.payload));
            }
        }
        return changed;
    }

    public Map<String, Object> getLatest(long sourceMetaKey) {
        List<AnalysisRow> rows = queryRows("sourceMetaKey = " + sourceMetaKey);
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        AnalysisRow latest = Collections.max(rows, Comparator.comparingInt(row -> row.version));
        return latest.payload;
    }

    public Map<String, Object> getAnalysis(long sourceMetaKey, String analysisId) {
        if (analysisId == null || analysisId.trim().isEmpty()) return getLatest(sourceMetaKey);
        for (AnalysisRow row : queryRows("sourceMetaKey = " + sourceMetaKey)) {
            if (analysisId.equals(row.payload.get("analysisId"))) return row.payload;
        }
        return Collections.emptyMap();
    }

    public Map<String, Object> getLatestSummary(long sourceMetaKey) {
        List<AnalysisRow> rows = queryRows("sourceMetaKey = " + sourceMetaKey);
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        AnalysisRow latest = Collections.max(rows, Comparator.comparingInt(row -> row.version));
        return latest.summary;
    }

    private void insertRow(long sourceMetaKey,
                           int version,
                           String analysisId,
                           String status,
                           String createdAt,
                           Map<String, Object> payload,
                           Map<String, Object> summary) {
        try {
            List<String> paths = Arrays.asList(
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".sourceMetaKey",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".analysisVersion",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".analysisId",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".status",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".schemaVersion",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".createdAt",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".summary",
                    IGinxConstants.DRAWING_ANALYSIS_PATH + ".payload"
            );
            long rowKey = nextRowKey();
            long[] keys = new long[]{rowKey};
            Object[] values = new Object[]{
                    new Long[]{Long.valueOf(sourceMetaKey)},
                    new Integer[]{Integer.valueOf(version)},
                    binaryColumn(analysisId),
                    binaryColumn(status),
                    binaryColumn(stringValue(payload.get("schemaVersion"), "1.0")),
                    binaryColumn(createdAt),
                    binaryColumn(objectMapper.writeValueAsString(summary)),
                    binaryColumn(objectMapper.writeValueAsString(payload))
            };
            List<DataType> types = Arrays.asList(
                    DataType.LONG,
                    DataType.INTEGER,
                    DataType.BINARY,
                    DataType.BINARY,
                    DataType.BINARY,
                    DataType.BINARY,
                    DataType.BINARY,
                    DataType.BINARY
            );
            iginxDao.insertColumnRecords(paths, keys, values, types);
        } catch (Exception e) {
            throw new RuntimeException("persist drawing analysis to IGinX failed: " + e.getMessage(), e);
        }
    }

    private List<AnalysisRow> queryRows(String whereClause) {
        SessionExecuteSqlResult result;
        try {
            String sql = "select * from " + IGinxConstants.DRAWING_ANALYSIS_PATH;
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql += " where " + whereClause.trim();
            }
            result = iginxDao.executeSql(sql + ";");
        } catch (Exception e) {
            // The namespace does not exist before the first successful extraction.
            return Collections.emptyList();
        }
        if (result == null || result.getPaths() == null || result.getValues() == null) {
            return Collections.emptyList();
        }

        List<String> paths = result.getPaths();
        Map<String, Integer> indexes = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            int dot = path.lastIndexOf('.');
            indexes.put(dot >= 0 ? path.substring(dot + 1) : path, Integer.valueOf(i));
        }

        List<AnalysisRow> rows = new ArrayList<AnalysisRow>();
        for (List<Object> values : result.getValues()) {
            String payloadText = valueAt(values, indexes.get("payload"));
            if (payloadText.isEmpty()) {
                continue;
            }
            AnalysisRow row = new AnalysisRow();
            row.sourceMetaKey = longValue(valueAt(values, indexes.get("sourceMetaKey")), 0L);
            row.version = intValue(valueAt(values, indexes.get("analysisVersion")), 0);
            row.payload = parseObject(payloadText);
            row.summary = parseObject(valueAt(values, indexes.get("summary")));
            if (row.summary.isEmpty()) {
                row.summary = buildSummary(row.payload);
            }
            rows.add(row);
        }
        return rows;
    }

    private int latestVersion(long sourceMetaKey) {
        int latest = 0;
        for (AnalysisRow row : queryRows("sourceMetaKey = " + sourceMetaKey)) {
            latest = Math.max(latest, row.version);
        }
        return latest;
    }

    private Map<String, Object> buildSummary(Map<String, Object> payload) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        Map<String, Object> classification = mapValue(payload.get("classification"));
        Map<String, Object> documentInfo = mapValue(payload.get("documentInfo"));
        Map<String, Object> structure = mapValue(payload.get("structure"));
        Map<String, Object> validation = mapValue(payload.get("validation"));

        summary.put("available", Boolean.TRUE);
        summary.put("analysisId", payload.get("analysisId"));
        summary.put("analysisVersion", payload.get("analysisVersion"));
        summary.put("status", payload.get("status"));
        summary.put("drawingType", classification.get("drawingType"));
        summary.put("documentClass", classification.get("documentClass"));
        summary.put("domain", classification.get("domain"));
        summary.put("structureKind", structure.get("kind"));
        summary.put("authority", structure.get("authority"));
        summary.put("objectName", firstNonEmpty(
                documentInfo.get("title"),
                payload.get("mainObjectName"),
                payload.get("mainObject")));
        summary.put("summaryText", payload.get("summaryText"));
        summary.put("confidence", firstNonNull(validation.get("score"), classification.get("confidence")));
        summary.put("partCount", Integer.valueOf(listSize(payload.get("occurrences"))));
        summary.put("dimensionCount", Integer.valueOf(listSize(payload.get("dimensions"))));
        summary.put("textCount", Integer.valueOf(listSize(payload.get("textBlocks"))));
        summary.put("issueCount", Integer.valueOf(listSize(validation.get("issues"))));
        return summary;
    }

    private String initialStatus(Map<String, Object> payload) {
        Map<String, Object> validation = mapValue(payload.get("validation"));
        if (Boolean.TRUE.equals(validation.get("requiresReview"))) {
            return "NEEDS_REVIEW";
        }
        double score = doubleValue(firstNonNull(validation.get("score"),
                mapValue(payload.get("classification")).get("confidence")), 0.0D);
        return score >= 0.90D ? "AUTO_ACCEPTED" : "NEEDS_REVIEW";
    }

    private Map<String, Object> parseObject(String json) {
        String text = safe(json);
        if (text.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid drawing analysis JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private int listSize(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonEmpty(Object... values) {
        for (Object value : values) {
            String text = safe(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String valueAt(List<Object> values, Integer index) {
        if (values == null || index == null || index.intValue() < 0 || index.intValue() >= values.size()) {
            return "";
        }
        Object value = values.get(index.intValue());
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8).trim();
        }
        return safe(value);
    }

    private byte[][] binaryColumn(String value) {
        return new byte[][]{safe(value).getBytes(StandardCharsets.UTF_8)};
    }

    private long nextRowKey() {
        long wallClock = System.currentTimeMillis() * 1000L;
        while (true) {
            long current = ROW_KEY.get();
            long next = Math.max(current + 1L, wallClock);
            if (ROW_KEY.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private String stringValue(Object value, String fallback) {
        String text = safe(value);
        return text.isEmpty() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        try {
            return Integer.parseInt(safe(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        try {
            return Long.parseLong(safe(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        try {
            return Double.parseDouble(safe(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static class AnalysisRow {
        private long sourceMetaKey;
        private int version;
        private Map<String, Object> payload;
        private Map<String, Object> summary;
    }
}
