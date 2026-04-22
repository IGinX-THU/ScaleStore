package com.storage.engine.model;

public class DataItem {
    private Integer id;
    private String logicalPath;
    private String dataType;     // timeseries, relational, image, document, keyvalue
    private String fileName;
    private Long fileSize;
    private String fileFormat;   // csv, txt, jpg, png, bmp, json, xml, yaml
    private String createTime;
    private Boolean isValid;
    private String knowledgeExtractStatus;
    private String contentPath;

    // For preview / access response
    private Object previewData;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getLogicalPath() { return logicalPath; }
    public void setLogicalPath(String logicalPath) { this.logicalPath = logicalPath; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public Boolean getIsValid() { return isValid; }
    public void setIsValid(Boolean valid) { isValid = valid; }

    public String getKnowledgeExtractStatus() { return knowledgeExtractStatus; }
    public void setKnowledgeExtractStatus(String knowledgeExtractStatus) { this.knowledgeExtractStatus = knowledgeExtractStatus; }

    public String getContentPath() { return contentPath; }
    public void setContentPath(String contentPath) { this.contentPath = contentPath; }

    public Object getPreviewData() { return previewData; }
    public void setPreviewData(Object previewData) { this.previewData = previewData; }
}
