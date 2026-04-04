package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.model.RestfulApiItem;
import com.storage.engine.service.GrpcApiCatalogService;
import com.storage.engine.service.RestfulApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InterfaceController {

    @Autowired
    private RestfulApiService restfulApiService;

    @Autowired
    private GrpcApiCatalogService grpcApiCatalogService;

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

    @GetMapping("/config/interfaces/java-grpc")
    public ResponseEntity<Response<List<RestfulApiItem>>> getAllJavaGrpcApis() {
        return ResponseEntity.ok(Response.success(grpcApiCatalogService.getAllJavaGrpcApis()));
    }

    @GetMapping("/config/interfaces/java-grpc/{id}")
    public ResponseEntity<Response<RestfulApiItem>> getJavaGrpcApiById(@PathVariable Integer id) {
        RestfulApiItem item = grpcApiCatalogService.getJavaGrpcApiById(id);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "Java gRPC API not found"));
        }
        return ResponseEntity.ok(Response.success(item));
    }

    @GetMapping("/config/interfaces/python-grpc")
    public ResponseEntity<Response<List<RestfulApiItem>>> getAllPythonGrpcApis() {
        return ResponseEntity.ok(Response.success(grpcApiCatalogService.getAllPythonGrpcApis()));
    }

    @GetMapping("/config/interfaces/python-grpc/{id}")
    public ResponseEntity<Response<RestfulApiItem>> getPythonGrpcApiById(@PathVariable Integer id) {
        RestfulApiItem item = grpcApiCatalogService.getPythonGrpcApiById(id);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "Python gRPC API not found"));
        }
        return ResponseEntity.ok(Response.success(item));
    }
}