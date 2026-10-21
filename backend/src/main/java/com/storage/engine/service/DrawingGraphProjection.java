package com.storage.engine.service;

import java.util.*;

/**
 * Pure mapping from the persisted drawing document to the small engineering ontology.
 * No names are used as identities, and unresolved dimensions never acquire an owner.
 */
public final class DrawingGraphProjection {
    public static final int SCHEMA_VERSION = 2;
    public final Map<String, Object> analysis = new LinkedHashMap<>();
    public final List<Map<String, Object>> components = new ArrayList<>();
    public final List<Map<String, Object>> dimensions = new ArrayList<>();
    public final List<Map<String, Object>> parts = new ArrayList<>();
    public final List<Map<String, Object>> dimensionLinks = new ArrayList<>();
    public String rootId;

    public static DrawingGraphProjection from(Map<String, Object> payload) {
        return new DrawingGraphProjection(payload);
    }

    private DrawingGraphProjection(Map<String, Object> payload) {
        String analysisId = text(payload.get("analysisId"));
        if (analysisId.isEmpty() || !(payload.get("sourceMetaKey") instanceof Number)) {
            throw new IllegalArgumentException("Persisted analysisId and sourceMetaKey are required");
        }
        long metaKey = ((Number) payload.get("sourceMetaKey")).longValue();
        Map<String, Object> document = map(payload.get("documentInfo"));
        Map<String, Object> structure = map(payload.get("structure"));
        String kind = text(structure.get("kind"));
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("sourceMetaKey", metaKey);
        common.put("analysisId", analysisId);
        analysis.putAll(common);
        analysis.put("graphId", analysisId + ":analysis");
        analysis.put("projectionSchemaVersion", SCHEMA_VERSION);
        analysis.put("name", "图纸解析");
        copy(analysis, payload, "analysisVersion", "status", "createdAt", "summaryText");
        copy(analysis, map(payload.get("classification")), "drawingType", "documentClass", "domain");
        analysis.put("structureKind", kind);
        copy(analysis, structure, "authority");
        Object score = map(payload.get("validation")).get("score");
        if (score == null) score = map(payload.get("classification")).get("confidence");
        if (score instanceof Number) analysis.put("confidence", score);

        Map<String, Map<String, Object>> definitions = index(payload.get("partDefinitions"), "partDefinitionId");
        Map<String, Map<String, Object>> occurrences = "FEATURE_TREE".equals(kind) || "NONE".equals(kind)
                ? new LinkedHashMap<>() : index(payload.get("occurrences"), "occurrenceId");
        Map<String, Map<String, Object>> features = index(payload.get("features"), "featureId");
        Map<String, List<String>> targets = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        Set<String> explicitRoots = strings(structure.get("rootOccurrenceIds"));
        for (Map.Entry<String, Map<String, Object>> entry : occurrences.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> occurrence = entry.getValue();
            String parent = text(occurrence.get("parentOccurrenceId"));
            parents.put(id, explicitRoots.contains(id) ? "" : parent);
        }

        // Reuse an explicit assembly occurrence only when it already contains other items.
        List<String> roots = new ArrayList<>();
        for (String id : occurrences.keySet()) {
            if (parents.get(id).isEmpty()) roots.add(id);
        }
        String rootOccurrence = roots.size() == 1 && parents.containsValue(roots.get(0)) ? roots.get(0) : "";
        rootId = analysisId + (rootOccurrence.isEmpty() ? ":root" : ":component:" + rootOccurrence);
        if (rootOccurrence.isEmpty()) {
            Map<String, Object> root = new LinkedHashMap<>(common);
            root.put("graphId", rootId);
            root.put("role", "ROOT");
            root.put("name", first(payload.get("mainObjectName"), document.get("title"), "图纸主体"));
            copy(root, document, "drawingNo", "material", "standard");
            // A unique definition in a single-part drawing is the subject, not a child BOM item.
            if ("FEATURE_TREE".equals(kind) && definitions.size() == 1) {
                Map<String, Object> definition = definitions.values().iterator().next();
                copy(root, definition, "name", "partNumber", "drawingNo", "material", "standard", "specification");
                targets.put(definitions.keySet().iterator().next(), Collections.singletonList(rootId));
            }
            components.add(root);
        }
        for (Map.Entry<String, Map<String, Object>> entry : occurrences.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> occurrence = entry.getValue();
            String definitionId = text(occurrence.get("partDefinitionId"));
            Map<String, Object> definition = definitions.getOrDefault(definitionId, Collections.emptyMap());
            Map<String, Object> component = new LinkedHashMap<>(common);
            component.put("graphId", analysisId + ":component:" + id);
            component.put("sourceId", id);
            component.put("role", id.equals(rootOccurrence) ? "ROOT" : "ITEM");
            copy(component, definition, "partNumber", "drawingNo", "material", "standard", "specification");
            copy(component, occurrence, "itemNo", "quantity", "unit", "material", "standard", "sourceType",
                    "confidence", "geometryEvidenceIds", "textEvidenceIds", "evidenceIds");
            component.put("name", first(occurrence.get("displayName"), definition.get("name"), id));
            String graphId = text(component.get("graphId"));
            targets.put(id, Collections.singletonList(graphId));
            if (!definitionId.isEmpty()) {
                targets.computeIfAbsent(definitionId, key -> new ArrayList<>()).add(graphId);
            }
            if (!id.equals(rootOccurrence)) {
                String parent = parents.get(id);
                if (!validParent(id, parent, parents)) {
                    if (!parent.isEmpty()) component.put("hierarchyIssue", "父项缺失、循环或层级过深，作为顶层项展示");
                    parent = "";
                }
                parts.add(edge(parent.isEmpty() ? rootId : analysisId + ":component:" + parent, graphId));
            }
            components.add(component);
        }

        // A single-part drawing still has a real engineering structure. Project each
        // parsed feature as a Component so Neo4j mirrors the analysis tree without
        // pretending that those features are BOM occurrences.
        if ("FEATURE_TREE".equals(kind)) {
            Map<String, String> featureParents = new LinkedHashMap<>();
            Map<String, Map<String, Object>> featureComponents = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : features.entrySet()) {
                String id = entry.getKey();
                Map<String, Object> feature = entry.getValue();
                String graphId = analysisId + ":component:feature:" + id;
                Map<String, Object> component = new LinkedHashMap<>(common);
                component.put("graphId", graphId);
                component.put("sourceId", id);
                component.put("role", "FEATURE");
                component.put("name", first(feature.get("name"), id));
                copy(component, feature, "kind", "confidence", "geometryEvidenceIds", "textEvidenceIds", "evidenceIds");
                featureParents.put(id, text(feature.get("parentRef")));
                featureComponents.put(id, component);
                targets.put(id, Collections.singletonList(graphId));
            }
            for (Map.Entry<String, Map<String, Object>> entry : featureComponents.entrySet()) {
                String id = entry.getKey();
                Map<String, Object> component = entry.getValue();
                String parent = featureParents.get(id);
                String parentGraphId = rootId;
                if (features.containsKey(parent) && validFeatureParent(id, parent, featureParents)) {
                    parentGraphId = analysisId + ":component:feature:" + parent;
                } else if (!parent.isEmpty() && !definitions.containsKey(parent)) {
                    component.put("hierarchyIssue", "父特征缺失、循环或层级过深，作为主体直属结构展示");
                }
                parts.add(edge(parentGraphId, text(component.get("graphId"))));
                components.add(component);
            }
        }

        int unresolved = 0;
        for (Map.Entry<String, Map<String, Object>> entry : index(payload.get("dimensions"), "dimensionId").entrySet()) {
            Map<String, Object> dimension = entry.getValue();
            String ref = text(dimension.get("targetRef"));
            String featureName = "";
            Set<String> visited = new HashSet<>();
            // FEATURE_TREE features are graph components and own their dimensions
            // directly. Other structure types keep the compact projection by
            // resolving feature dimensions to the containing BOM occurrence.
            while (!targets.containsKey(ref) && features.containsKey(ref) && visited.add(ref)) {
                Map<String, Object> feature = features.get(ref);
                if (featureName.isEmpty()) featureName = text(feature.get("name"));
                ref = text(feature.get("parentRef"));
                if (ref.isEmpty() && "FEATURE_TREE".equals(kind)) {
                    ref = "__single_part_root__";
                    break;
                }
            }
            if (featureName.isEmpty() && features.containsKey(ref)) {
                featureName = text(features.get(ref).get("name"));
            }
            List<String> owners = "__single_part_root__".equals(ref)
                    ? Collections.singletonList(rootId) : targets.get(ref);
            if (owners == null || owners.isEmpty()) {
                unresolved++;
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>(common);
            String dimensionId = analysisId + ":dimension:" + entry.getKey();
            node.put("graphId", dimensionId);
            node.put("sourceId", entry.getKey());
            node.put("name", first(dimension.get("rawText"), entry.getKey()));
            copy(node, dimension, "rawText", "kind", "nominal", "unit", "upperTolerance", "lowerTolerance",
                    "fit", "quantityPattern", "confidence", "evidenceIds");
            if (!featureName.isEmpty()) node.put("featureName", featureName);
            dimensions.add(node);
            for (String owner : new LinkedHashSet<>(owners)) dimensionLinks.add(edge(owner, dimensionId));
        }
        Map<String, Long> childCounts = countsBySource(parts);
        Map<String, Long> dimensionCounts = countsBySource(dimensionLinks);
        for (Map<String, Object> component : components) {
            String id = text(component.get("graphId"));
            component.put("childCount", childCounts.getOrDefault(id, 0L));
            component.put("dimensionCount", dimensionCounts.getOrDefault(id, 0L));
        }
        analysis.put("componentCount", components.size());
        analysis.put("dimensionCount", dimensions.size());
        analysis.put("unassignedDimensionCount", unresolved);
    }

    private static boolean validParent(String id, String parent, Map<String, String> parents) {
        Set<String> visited = new HashSet<>();
        visited.add(id);
        while (!parent.isEmpty()) {
            if (!parents.containsKey(parent) || !visited.add(parent) || visited.size() > 63) return false;
            parent = parents.get(parent);
        }
        return true;
    }

    private static boolean validFeatureParent(String id, String parent, Map<String, String> parents) {
        Set<String> visited = new HashSet<>();
        visited.add(id);
        while (parents.containsKey(parent)) {
            if (!visited.add(parent) || visited.size() > 63) return false;
            parent = parents.get(parent);
        }
        return true;
    }

    private static Map<String, Object> edge(String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }

    private static Map<String, Long> countsBySource(List<Map<String, Object>> edges) {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> edge : edges) counts.merge(text(edge.get("source")), 1L, Long::sum);
        return counts;
    }

    private static Map<String, Map<String, Object>> index(Object value, String idField) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                Map<String, Object> row = map(item);
                String id = text(row.get(idField));
                if (!id.isEmpty()) result.putIfAbsent(id, row);
            }
        }
        return result;
    }

    private static Set<String> strings(Object value) {
        Set<String> result = new HashSet<>();
        if (value instanceof List) for (Object item : (List<?>) value) result.add(text(item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String && !text(value).isEmpty() || value instanceof Number || value instanceof Boolean) {
                target.put(key, value);
            } else if (value instanceof List) {
                List<String> refs = new ArrayList<>();
                for (Object item : (List<?>) value) if (item instanceof String) refs.add((String) item);
                target.put(key, refs);
            }
        }
    }

    private static String first(Object... values) {
        for (Object value : values) if (!text(value).isEmpty()) return text(value);
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
