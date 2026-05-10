package com.storage.engine.model;

public class DataSourceInfo {
    private Integer id;
    private String name;
    private String ip;
    private String port;
    private String type;
    private String schemaPrefix;
    private String dataPrefix;
    private Boolean connected;
    private Boolean isDefault;
    private Long dataSize;
    private String sourceGroup;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSchemaPrefix() { return schemaPrefix; }
    public void setSchemaPrefix(String schemaPrefix) { this.schemaPrefix = schemaPrefix; }

    public String getDataPrefix() { return dataPrefix; }
    public void setDataPrefix(String dataPrefix) { this.dataPrefix = dataPrefix; }

    public Boolean getConnected() { return connected; }
    public void setConnected(Boolean connected) { this.connected = connected; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean aDefault) { isDefault = aDefault; }

    public Long getDataSize() { return dataSize; }
    public void setDataSize(Long dataSize) { this.dataSize = dataSize; }

    public String getSourceGroup() { return sourceGroup; }
    public void setSourceGroup(String sourceGroup) { this.sourceGroup = sourceGroup; }
}
