package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.service.DrawingAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping
public class DrawingAnalysisController {

    @Autowired
    private DrawingAnalysisService drawingAnalysisService;

    @GetMapping("/access/drawing-analysis")
    public ResponseEntity<Response<Map<String, Object>>> getLatest(@RequestParam("metaKey") long metaKey,
            @RequestParam(value = "analysisId", required = false) String analysisId) {
        Map<String, Object> analysis = drawingAnalysisService.getAnalysis(metaKey, analysisId);
        if (analysis.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Response.error(404, "Drawing analysis not found for metaKey: " + metaKey));
        }
        return ResponseEntity.ok(Response.success(analysis));
    }

}
