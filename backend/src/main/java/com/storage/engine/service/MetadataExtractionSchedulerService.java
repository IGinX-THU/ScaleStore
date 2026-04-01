package com.storage.engine.service;

import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataExtractResult;
import com.storage.engine.model.Node;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetadataExtractionSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataExtractionSchedulerService.class);

    private static final int MAX_EVENT_CACHE = 300;

    @Value("${metadata.extraction.scan-batch-size:20}")
    private int scanBatchSize;

    @Autowired
    private AccessService accessService;

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private MetadataUdfService metadataUdfService;

    @Autowired
    private NodeService nodeService;

    private final Set<Integer> processingIds = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Deque<AgentMessageEvent> eventBuffer = new LinkedList<AgentMessageEvent>();
    private final AtomicLong eventSeq = new AtomicLong(0L);

    @PostConstruct
    public void init() {
        publishEvent("info", "初始化", "元数据定时抽取调度已启动，等待扫描任务。", "IGinX-Scheduler");
    }

    @Scheduled(fixedDelayString = "${metadata.extraction.scan-interval-ms:60000}")
    public void scanAndExtract() {
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

        int maxPerScan = Math.max(1, scanBatchSize);
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
        DataItem latest = accessService.getMetaByPath(snapshot.getLogicalPath());
        if (latest == null || latest.getId() == null) {
            return;
        }

        String latestStatus = normalizeStatus(latest.getKnowledgeExtractStatus());
        if (!isPendingLike(latestStatus)) {
            return;
        }

        long id = latest.getId().longValue();
        String logicalPath = safe(latest.getLogicalPath());
        String dataType = safe(latest.getDataType());
        String agentName = pickAgentName(latest);

        safeUpdateStatus(id, "PROCESSING");
        publishEvent(
                "running",
                "进行中",
                "正在执行UDF抽取，逻辑路径 " + logicalPath + "，类型 " + dataType + "。",
                agentName);

        try {
            MetadataExtractResult result = metadataUdfService.extractByUdf(latest);
            logExtractResult(latest, result);
            safeUpdateStatus(id, "SUCCESS");
            publishEvent(
                    "success",
                    "完成",
                    buildSuccessText(logicalPath, dataType, result),
                    agentName);
        } catch (Exception e) {
            safeUpdateStatus(id, "FAILED");
            logger.error("元数据定时抽取失败: id={}, path={}, error={}", id, logicalPath, e.getMessage(), e);
            publishEvent(
                    "warn",
                    "失败",
                    "UDF抽取失败，逻辑路径 " + logicalPath + "，原因: " + safe(e.getMessage()),
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

    private String buildSuccessText(String logicalPath, String dataType, MetadataExtractResult result) {
        String dt = safe(dataType).toLowerCase(Locale.ROOT);
        if ("relational".equals(dt) || "timeseries".equals(dt) || "keyvalue".equals(dt)) {
            int fieldCount = result == null || result.getFields() == null ? 0 : result.getFields().size();
            String text = "UDF抽取完成，逻辑路径 " + logicalPath + "，field实体 " + fieldCount + " 个。";
            String udfMessage = result == null ? "" : safe(result.getLlmResponse());
            if (!udfMessage.isEmpty()) {
                text = text + " " + udfMessage;
            }
            return text;
        }

        int entityCount = result == null || result.getEntities() == null ? 0 : result.getEntities().size();
        int tripleCount = result == null || result.getTriples() == null ? 0 : result.getTriples().size();
        String text = "UDF抽取完成，逻辑路径 " + logicalPath + "，实体 " + entityCount + " 个，三元组 " + tripleCount + " 条。";
        String udfMessage = result == null ? "" : safe(result.getLlmResponse());
        if (!udfMessage.isEmpty()) {
            text = text + " " + udfMessage;
        }
        return text;
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

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
