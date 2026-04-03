package com.storage.engine.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识抽取统一结果对象，供不同数据类型的抽取适配器返回。
 */
public class MetadataExtractResult {

    private List<String> fields = new ArrayList<String>();
    private String fieldKind = "field";
    private List<String> entities = new ArrayList<String>();
    private List<SemanticTriple> triples = new ArrayList<SemanticTriple>();
    private boolean llmUsed = false;
    private String llmResponse = "";
    private String llmError = "";

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    public String getFieldKind() {
        return fieldKind;
    }

    public void setFieldKind(String fieldKind) {
        this.fieldKind = fieldKind;
    }

    public List<String> getEntities() {
        return entities;
    }

    public void setEntities(List<String> entities) {
        this.entities = entities;
    }

    public List<SemanticTriple> getTriples() {
        return triples;
    }

    public void setTriples(List<SemanticTriple> triples) {
        this.triples = triples;
    }

    public boolean isLlmUsed() {
        return llmUsed;
    }

    public void setLlmUsed(boolean llmUsed) {
        this.llmUsed = llmUsed;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public String getLlmError() {
        return llmError;
    }

    public void setLlmError(String llmError) {
        this.llmError = llmError;
    }

    public static class SemanticTriple {
        private String subject;
        private String predicate;
        private String object;

        public SemanticTriple() {
        }

        public SemanticTriple(String subject, String predicate, String object) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getPredicate() {
            return predicate;
        }

        public void setPredicate(String predicate) {
            this.predicate = predicate;
        }

        public String getObject() {
            return object;
        }

        public void setObject(String object) {
            this.object = object;
        }

        @Override
        public String toString() {
            return "(" + subject + " -" + predicate + "-> " + object + ")";
        }
    }
}
