package com.storage.engine.controller;

import com.storage.engine.model.DataItem;
import com.storage.engine.model.Response;
import com.storage.engine.service.AccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class AccessController {

    @Autowired
    private AccessService accessService;

    /**
     * Access data by logical path - returns metadata + preview data.
     * GET /access/data?logicalPath=/project/sensor/data
     */
    @GetMapping("/access/data")
    public ResponseEntity<Response<DataItem>> accessData(
            @RequestParam("logicalPath") String logicalPath,
            @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            DataItem item = accessService.accessData(logicalPath, fileName);
            if (item != null) {
                return ResponseEntity.ok(Response.success(item));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error(404, "Data not found for path: " + logicalPath));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error(500, "Access failed: " + e.getMessage()));
        }
    }

    /**
     * Download data file by logical path.
     * GET /access/download?logicalPath=/project/sensor/data
     */
    @GetMapping("/access/download")
    public ResponseEntity<byte[]> downloadData(
            @RequestParam("logicalPath") String logicalPath,
            @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            DataItem meta = accessService.getMetaByAccessPath(logicalPath, fileName);
            if (meta == null) {
                return ResponseEntity.notFound().build();
            }

            byte[] data = accessService.downloadData(logicalPath, fileName);
            if (data == null || data.length == 0) {
                return ResponseEntity.noContent().build();
            }

            String downloadName = meta.getFileName();
            if (downloadName == null || downloadName.isEmpty()) {
                downloadName = "download." + (meta.getFileFormat() != null ? meta.getFileFormat() : "dat");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(getMediaType(meta.getFileFormat(), meta.getDataType()));
            headers.setContentLength(data.length);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8.name()) + "\"");
            // Allow CORS to expose these headers
            headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    "Content-Disposition, Content-Length, Content-Type");

            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * List all stored data items.
     * GET /access/list
     */
    @GetMapping("/access/list")
    public ResponseEntity<Response<List<DataItem>>> listData() {
        try {
            List<DataItem> items = accessService.getAllMeta();
            return ResponseEntity.ok(Response.success(items));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error(500, "Failed to list data: " + e.getMessage()));
        }
    }

    private MediaType getMediaType(String fileFormat, String dataType) {
        if (fileFormat == null) return MediaType.APPLICATION_OCTET_STREAM;
        switch (fileFormat.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "png":
                return MediaType.IMAGE_PNG;
            case "bmp":
                return MediaType.parseMediaType("image/bmp");
            case "json":
                return MediaType.APPLICATION_JSON;
            case "xml":
                return MediaType.APPLICATION_XML;
            case "csv":
                return MediaType.parseMediaType("text/csv");
            case "txt":
                return MediaType.TEXT_PLAIN;
            case "yaml":
            case "yml":
                return MediaType.parseMediaType("text/yaml");
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
