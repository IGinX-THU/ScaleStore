package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.service.MetadataExtractionSchedulerService;
import com.storage.engine.service.MetadataKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class MetadataController {

    @Autowired
    private MetadataKnowledgeService metadataKnowledgeService;

    @Autowired
    private MetadataExtractionSchedulerService metadataExtractionSchedulerService;

    /**
     * Build/query metadata graph from Neo4j.
     * GET /metadata/graph?logicalPath=/test&limit=300
     */
    @GetMapping("/metadata/graph")
    public Response<Map<String, Object>> getGraph(
            @RequestParam(value = "logicalPath", required = false) String logicalPath,
            @RequestParam(value = "limit", required = false, defaultValue = "300") int limit) {
        Map<String, Object> graph = metadataKnowledgeService.getGraph(logicalPath, limit);
        return Response.success(graph);
    }

    /**
     * Metadata query endpoint.
     * mode=system: structured query by logicalPath/dataType/keyword.
     * mode=llm: natural language query by q.
     */
    @GetMapping("/metadata/query")
    public Response<Map<String, Object>> query(
            @RequestParam(value = "mode", required = false, defaultValue = "system") String mode,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "logicalPath", required = false) String logicalPath,
            @RequestParam(value = "dataType", required = false) String dataType,
            @RequestParam(value = "keyword", required = false) String keyword) {

        Map<String, Object> result;
        if ("llm".equalsIgnoreCase(mode)) {
            result = metadataKnowledgeService.queryByLlmNaturalLanguage(q);
        } else {
            result = metadataKnowledgeService.queryBySystemFilters(logicalPath, dataType, keyword);
        }
        return Response.success(result);
    }

    /**
     * Real extraction events for frontend agent stream.
     * GET /metadata/extraction/events?since=0&limit=40
     */
    @GetMapping("/metadata/extraction/events")
    public Response<Map<String, Object>> extractionEvents(
            @RequestParam(value = "since", required = false, defaultValue = "0") long since,
            @RequestParam(value = "limit", required = false, defaultValue = "40") int limit) {

        List<AgentMessageEvent> events = metadataExtractionSchedulerService.listEventsSince(since, limit);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("events", events);
        payload.put("latestSeq", metadataExtractionSchedulerService.getLatestEventSeq());
        payload.put("serverTime", System.currentTimeMillis());

        return Response.success(payload);
    }
}
