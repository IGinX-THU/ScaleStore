package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.model.RestfulApiItem;
import com.storage.engine.service.RestfulApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RestfulApiController {

    @Autowired
    private RestfulApiService restfulApiService;

    @GetMapping("/config/interfaces/restful")
    public ResponseEntity<Response<List<RestfulApiItem>>> getAllRestfulApis() {
        return ResponseEntity.ok(Response.success(restfulApiService.getAllRestfulApis()));
    }

    @GetMapping("/config/interfaces/restful/{id}")
    public ResponseEntity<Response<RestfulApiItem>> getRestfulApiById(@PathVariable Integer id) {
        RestfulApiItem item = restfulApiService.getRestfulApiById(id);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "RESTful API not found"));
        }
        return ResponseEntity.ok(Response.success(item));
    }

    @PostMapping("/config/interfaces/restful")
    public ResponseEntity<Response<RestfulApiItem>> createRestfulApi(@RequestBody RestfulApiItem item) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(restfulApiService.createRestfulApi(item)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.error(400, e.getMessage()));
        }
    }

    @PutMapping("/config/interfaces/restful/{id}")
    public ResponseEntity<Response<RestfulApiItem>> updateRestfulApi(@PathVariable Integer id,
                                                                      @RequestBody RestfulApiItem patch) {
        try {
            RestfulApiItem item = restfulApiService.updateRestfulApi(id, patch);
            if (item == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "RESTful API not found"));
            }
            return ResponseEntity.ok(Response.success(item));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/config/interfaces/restful/{id}")
    public ResponseEntity<Response<Void>> deleteRestfulApi(@PathVariable Integer id) {
        boolean deleted = restfulApiService.deleteRestfulApi(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "RESTful API not found"));
        }
        return ResponseEntity.ok(Response.success());
    }
}
