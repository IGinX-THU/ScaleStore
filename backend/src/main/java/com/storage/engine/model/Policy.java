package com.storage.engine.model;

public class Policy {
    private Boolean extractionEnabled;
    private Long extractionScanIntervalMs;
    private Integer metadataGraphMaxNodes;

    private String extractionEnabledSource;
    private String extractionScanIntervalMsSource;
    private String metadataGraphMaxNodesSource;

    private String extractionEnabledDesc;
    private String extractionScanIntervalMsDesc;
    private String metadataGraphMaxNodesDesc;

    public Boolean getExtractionEnabled() {
        return extractionEnabled;
    }

    public void setExtractionEnabled(Boolean extractionEnabled) {
        this.extractionEnabled = extractionEnabled;
    }

    public Long getExtractionScanIntervalMs() {
        return extractionScanIntervalMs;
    }

    public void setExtractionScanIntervalMs(Long extractionScanIntervalMs) {
        this.extractionScanIntervalMs = extractionScanIntervalMs;
    }

    public Integer getMetadataGraphMaxNodes() {
        return metadataGraphMaxNodes;
    }

    public void setMetadataGraphMaxNodes(Integer metadataGraphMaxNodes) {
        this.metadataGraphMaxNodes = metadataGraphMaxNodes;
    }

    public String getExtractionEnabledSource() {
        return extractionEnabledSource;
    }

    public void setExtractionEnabledSource(String extractionEnabledSource) {
        this.extractionEnabledSource = extractionEnabledSource;
    }

    public String getExtractionScanIntervalMsSource() {
        return extractionScanIntervalMsSource;
    }

    public void setExtractionScanIntervalMsSource(String extractionScanIntervalMsSource) {
        this.extractionScanIntervalMsSource = extractionScanIntervalMsSource;
    }

    public String getMetadataGraphMaxNodesSource() {
        return metadataGraphMaxNodesSource;
    }

    public void setMetadataGraphMaxNodesSource(String metadataGraphMaxNodesSource) {
        this.metadataGraphMaxNodesSource = metadataGraphMaxNodesSource;
    }

    public String getExtractionEnabledDesc() {
        return extractionEnabledDesc;
    }

    public void setExtractionEnabledDesc(String extractionEnabledDesc) {
        this.extractionEnabledDesc = extractionEnabledDesc;
    }

    public String getExtractionScanIntervalMsDesc() {
        return extractionScanIntervalMsDesc;
    }

    public void setExtractionScanIntervalMsDesc(String extractionScanIntervalMsDesc) {
        this.extractionScanIntervalMsDesc = extractionScanIntervalMsDesc;
    }

    public String getMetadataGraphMaxNodesDesc() {
        return metadataGraphMaxNodesDesc;
    }

    public void setMetadataGraphMaxNodesDesc(String metadataGraphMaxNodesDesc) {
        this.metadataGraphMaxNodesDesc = metadataGraphMaxNodesDesc;
    }

}
