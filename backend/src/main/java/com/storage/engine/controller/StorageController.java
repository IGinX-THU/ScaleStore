package com.storage.engine.controller;

import com.storage.engine.model.AddStorageEngineRequest;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.Response;
import com.storage.engine.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
public class StorageController {

    @Autowired
    private StorageService storageService;

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
     *   - dataType: data type (timeseries/relational/image/document/keyvalue)
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
}
