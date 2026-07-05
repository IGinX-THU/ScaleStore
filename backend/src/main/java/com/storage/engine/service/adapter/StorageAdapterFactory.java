package com.storage.engine.service.adapter;

import com.storage.engine.constant.IGinxConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory that returns the appropriate StorageAdapter for a given data type.
 */
@Component
public class StorageAdapterFactory {

    private final Map<String, StorageAdapter> adapterMap = new HashMap<>();

    @Autowired
    public StorageAdapterFactory(
            TimeSeriesAdapter timeSeriesAdapter,
            RelationalAdapter relationalAdapter,
            FileAdapter fileAdapter,
            DocumentAdapter documentAdapter,
            KeyValueAdapter keyValueAdapter) {

        adapterMap.put(IGinxConstants.TYPE_TIMESERIES, timeSeriesAdapter);
        adapterMap.put(IGinxConstants.TYPE_RELATIONAL, relationalAdapter);
        adapterMap.put(IGinxConstants.TYPE_FILE, fileAdapter);
        adapterMap.put(IGinxConstants.TYPE_DOCUMENT, documentAdapter);
        adapterMap.put(IGinxConstants.TYPE_KEYVALUE, keyValueAdapter);
    }

    /**
     * Get the adapter for the given data type.
     *
     * @param dataType e.g., "timeseries", "relational", "file", "document", "keyvalue"
     * @return the matching StorageAdapter
     * @throws IllegalArgumentException if the data type is not supported
     */
    public StorageAdapter getAdapter(String dataType) {
        StorageAdapter adapter = adapterMap.get(dataType);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported data type: " + dataType
                    + ". Supported: " + adapterMap.keySet());
        }
        return adapter;
    }

    /**
     * Check whether a data type is supported.
     */
    public boolean isSupported(String dataType) {
        return adapterMap.containsKey(dataType);
    }
}
