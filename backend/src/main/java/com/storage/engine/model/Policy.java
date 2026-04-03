package com.storage.engine.model;

public class Policy {
    private Boolean extractionEnabled;
    private Long extractionScanIntervalMs;
    private Integer extractionScanBatchSize;

    private String extractionEnabledSource;
    private String extractionScanIntervalMsSource;
    private String extractionScanBatchSizeSource;

    private String extractionEnabledDesc;
    private String extractionScanIntervalMsDesc;
    private String extractionScanBatchSizeDesc;

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

    public Integer getExtractionScanBatchSize() {
        return extractionScanBatchSize;
    }

    public void setExtractionScanBatchSize(Integer extractionScanBatchSize) {
        this.extractionScanBatchSize = extractionScanBatchSize;
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

    public String getExtractionScanBatchSizeSource() {
        return extractionScanBatchSizeSource;
    }

    public void setExtractionScanBatchSizeSource(String extractionScanBatchSizeSource) {
        this.extractionScanBatchSizeSource = extractionScanBatchSizeSource;
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

    public String getExtractionScanBatchSizeDesc() {
        return extractionScanBatchSizeDesc;
    }

    public void setExtractionScanBatchSizeDesc(String extractionScanBatchSizeDesc) {
        this.extractionScanBatchSizeDesc = extractionScanBatchSizeDesc;
    }
}
