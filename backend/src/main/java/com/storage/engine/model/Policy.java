package com.storage.engine.model;

public class Policy {
    private Boolean extractionEnabled;
    private Long extractionScanIntervalMs;

    private String extractionEnabledSource;
    private String extractionScanIntervalMsSource;

    private String extractionEnabledDesc;
    private String extractionScanIntervalMsDesc;

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
}
