package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.model.AgentMessageEvent;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.MetadataSemanticLeafCallbackRequest;
import com.storage.engine.service.AccessService;
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
    private AccessService accessService;

    @Autowired
    private MetadataExtractionSchedulerService metadataExtractionSchedulerService;

    /**
     * Build/query metadata graph from Neo4j.
     * GET /metadata/graph?logicalPath=/test&maxNodes=200
     */
    @GetMapping("/metadata/graph")
    public Response<Map<String, Object>> getGraph(
            @RequestParam(value = "logicalPath", required = false) String logicalPath,
            @RequestParam(value = "maxNodes", required = false) Integer maxNodes) {
        Map<String, Object> graph = metadataKnowledgeService.getGraph(logicalPath, maxNodes == null ? 0 : maxNodes);
        return Response.success(graph);
    }

    /**
     * Metadata query endpoint.
     * Structured query by logicalPath/dataType/keyword.
     */
    @GetMapping("/metadata/query")
    public Response<Map<String, Object>> query(
            @RequestParam(value = "logicalPath", required = false) String logicalPath,
            @RequestParam(value = "dataType", required = false) String dataType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "expandRelations", required = false) Boolean expandRelations) {

        Map<String, Object> result = metadataKnowledgeService.queryBySystemFilters(
                logicalPath, dataType, keyword, expandRelations == null || expandRelations.booleanValue());
        return Response.success(result);
    }

    /**
     * Query one storage.meta row by key.
     * GET /metadata/meta?key=123
     */
    @GetMapping("/metadata/meta")
    public Response<DataItem> getMetaByKey(@RequestParam("key") Long key) {
        DataItem item = accessService.getMetaByKey(key == null ? -1L : key.longValue());
        return Response.success(item);
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

    @PostMapping("/metadata/extraction/semantic/leaf-callback")
    public Response<Void> leafSemanticCallback(@RequestBody MetadataSemanticLeafCallbackRequest request) {
        metadataExtractionSchedulerService.handleLeafSemanticCallback(request);
        return Response.success();
    }

    @PostMapping("/metadata/extraction/semantic/leaf-start-callback")
    public Response<Void> leafSemanticStartCallback(@RequestBody MetadataSemanticLeafCallbackRequest request) {
        metadataExtractionSchedulerService.handleLeafSemanticStartCallback(request);
        return Response.success();
    }

    @PostMapping("/metadata/extraction/semantic/directory-callback")
    public Response<Void> directorySemanticCallback(@RequestBody MetadataSemanticLeafCallbackRequest request) {
        metadataExtractionSchedulerService.handleDirectorySemanticCallback(request);
        return Response.success();
    }

    @PostMapping("/metadata/extraction/semantic/directory-start-callback")
    public Response<Void> directorySemanticStartCallback(@RequestBody MetadataSemanticLeafCallbackRequest request) {
        metadataExtractionSchedulerService.handleDirectorySemanticStartCallback(request);
        return Response.success();
    }
}
