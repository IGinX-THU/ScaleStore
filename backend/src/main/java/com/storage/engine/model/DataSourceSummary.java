package com.storage.engine.model;

import java.util.List;

public class DataSourceSummary {
    private Long totalDataSize;
    private List<DataSourceInfo> dataSources;

    public Long getTotalDataSize() { return totalDataSize; }
    public void setTotalDataSize(Long totalDataSize) { this.totalDataSize = totalDataSize; }

    public List<DataSourceInfo> getDataSources() { return dataSources; }
    public void setDataSources(List<DataSourceInfo> dataSources) { this.dataSources = dataSources; }
}
