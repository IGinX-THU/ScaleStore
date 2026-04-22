package com.storage.engine.model;

public class Policy {
    private Boolean extractionEnabled;
    private Long extractionScanIntervalMs;
    private Integer metadataGraphMaxTriples;

    private String extractionEnabledSource;
    private String extractionScanIntervalMsSource;
    private String metadataGraphMaxTriplesSource;

    private String extractionEnabledDesc;
    private String extractionScanIntervalMsDesc;
    private String metadataGraphMaxTriplesDesc;

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

    public Integer getMetadataGraphMaxTriples() {
        return metadataGraphMaxTriples;
    }

    public void setMetadataGraphMaxTriples(Integer metadataGraphMaxTriples) {
        this.metadataGraphMaxTriples = metadataGraphMaxTriples;
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

    public String getMetadataGraphMaxTriplesSource() {
        return metadataGraphMaxTriplesSource;
    }

    public void setMetadataGraphMaxTriplesSource(String metadataGraphMaxTriplesSource) {
        this.metadataGraphMaxTriplesSource = metadataGraphMaxTriplesSource;
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

    public String getMetadataGraphMaxTriplesDesc() {
        return metadataGraphMaxTriplesDesc;
    }

    public void setMetadataGraphMaxTriplesDesc(String metadataGraphMaxTriplesDesc) {
        this.metadataGraphMaxTriplesDesc = metadataGraphMaxTriplesDesc;
    }
}
