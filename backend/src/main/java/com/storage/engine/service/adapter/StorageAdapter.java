package com.storage.engine.service.adapter;

import com.storage.engine.model.MetadataExtractResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Adapter interface for heterogeneous data operations.
 * Each data type (timeseries, relational, image, document, keyvalue)
 * provides one implementation for:
 * 1) store
 * 2) access preview/download
 * 3) metadata extraction
 */
public interface StorageAdapter {

    /**
     * Return the data type identifier (e.g., "timeseries").
     */
    String getDataType();

    /**
     * Return supported file extensions (e.g., ["csv", "txt"]).
     */
    List<String> getSupportedFormats();

    /**
     * Store the uploaded file content into IGinX under the given iginxPath.
     *
     * @param file      the uploaded file
     * @param iginxPath IGinX data path (e.g., "data.project.sensor")
     */
    void store(MultipartFile file, String iginxPath) throws Exception;

    /**
     * Query and return preview data suitable for front-end rendering.
     *
     * @param iginxPath IGinX data path
     * @param limit     max rows/items for preview
     * @return a structured preview object (Map, String, etc.)
     */
    Object getPreviewData(String iginxPath, int limit) throws Exception;

    /**
     * Retrieve raw bytes for file download / reconstruction.
     *
     * @param iginxPath IGinX data path
     * @return byte array suitable for HTTP download
     */
    byte[] getDownloadBytes(String iginxPath) throws Exception;
}
