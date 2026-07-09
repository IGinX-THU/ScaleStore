package com.storage.engine.model;

public class MetadataSemanticLeafCallbackRequest {

    private Long metaKey;
    private String status;
    private String keywords;
    private String message;
    private String logicalPath;
    private String assetPath;
    private String fileName;
    private String dataType;
    private String fileFormat;

    public Long getMetaKey() {
        return metaKey;
    }

    public void setMetaKey(Long metaKey) {
        this.metaKey = metaKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getLogicalPath() {
        return logicalPath;
    }

    public void setLogicalPath(String logicalPath) {
        this.logicalPath = logicalPath;
    }

    public String getAssetPath() {
        return assetPath;
    }

    public void setAssetPath(String assetPath) {
        this.assetPath = assetPath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }
}
