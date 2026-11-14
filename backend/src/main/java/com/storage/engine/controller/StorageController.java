package com.storage.engine.controller;

import com.storage.engine.model.AddStorageEngineRequest;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.Response;
import com.storage.engine.model.DataSourceSummary;
import com.storage.engine.service.DataSourceService;
import com.storage.engine.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StorageController {

    private static final int MAX_BATCH_UPLOAD_FILES = 20;

    @Autowired
    private StorageService storageService;

    @Autowired
    private DataSourceService dataSourceService;

    @PostMapping("/storage/sources")
    public ResponseEntity<Response<Map<String, Object>>> addStorageSource(@RequestBody AddStorageEngineRequest request) {
        try {
            Map<String, Object> result = storageService.addExternalStorageEngine(request);
            return ResponseEntity.ok(Response.success(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Response.error(400, e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error(500, "Add storage source failed: " + e.getMessage()));
        }
    }

    /**
     * Upload and store a file.
     * POST /storage
     * Parameters:
     *   - file: the uploaded file (multipart)
     *   - logicalPath: logical storage path (e.g., /project/sensor/data)
     *   - dataType: data type (timeseries/relational/file/document/keyvalue)
     */
    @PostMapping("/storage")
    public ResponseEntity<Response<DataItem>> storeData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("logicalPath") String logicalPath,
            @RequestParam("dataType") String dataType) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Response.error(400, "File is empty"));
            }
            if (logicalPath == null || logicalPath.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Response.error(400, "Logical path is required"));
            }
            if (dataType == null || dataType.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Response.error(400, "Data type is required"));
            }

            DataItem item = storageService.storeData(file, logicalPath, dataType);
            return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(item));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Response.error(400, e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error(500, "Storage failed: " + e.getMessage()));
        }
    }

    /**
     * Upload multiple files to the same logical path in one request.
     * Each file is stored independently so one failure does not hide successful uploads.
     */
    @PostMapping("/storage/batch")
    public ResponseEntity<Response<Map<String, Object>>> storeDataBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("logicalPath") String logicalPath,
            @RequestParam("dataType") String dataType) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Response.error(400, "At least one file is required"));
        }
        if (files.length > MAX_BATCH_UPLOAD_FILES) {
            return ResponseEntity.badRequest().body(Response.error(400,
                    "A maximum of " + MAX_BATCH_UPLOAD_FILES + " files can be uploaded at once"));
        }
        if (logicalPath == null || logicalPath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Response.error(400, "Logical path is required"));
        }
        if (dataType == null || dataType.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Response.error(400, "Data type is required"));
        }

        List<DataItem> items = new ArrayList<>();
        List<Map<String, String>> failures = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("File is empty");
                }
                items.add(storageService.storeData(file, logicalPath, dataType));
            } catch (Exception e) {
                Map<String, String> failure = new LinkedHashMap<>();
                String fileName = file == null || file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename();
                failure.put("fileName", fileName);
                failure.put("message", e.getMessage() == null ? "Storage failed" : e.getMessage());
                failures.add(failure);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", files.length);
        result.put("successCount", items.size());
        result.put("failureCount", failures.size());
        result.put("items", items);
        result.put("failures", failures);

        HttpStatus responseStatus = failures.isEmpty() ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(responseStatus).body(Response.success(result));
    }

    @GetMapping("/storage/datasources")
    public ResponseEntity<Response<DataSourceSummary>> getDataSourceSummary() {
        try {
            return ResponseEntity.ok(Response.success(dataSourceService.getDataSourceSummary()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error(500, "Get data source summary failed: " + e.getMessage()));
        }
    }
}
