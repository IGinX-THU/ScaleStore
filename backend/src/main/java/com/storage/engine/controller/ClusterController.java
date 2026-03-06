package com.storage.engine.controller;

import com.storage.engine.constant.ResultCode;
import com.storage.engine.model.Node;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import com.storage.engine.model.Response;
import com.storage.engine.service.NodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ClusterController {

    @Autowired
    private NodeService nodeService;

    @GetMapping("/config/nodes")
    public ResponseEntity<Response<List<Node>>> getAllNodes() {
        return ResponseEntity.ok(Response.success(nodeService.getAllNodes()));
    }

    @PostMapping("/config/nodes")
    public ResponseEntity<Response<NodeDeployTaskStatus>> createNode(@RequestBody NodeDeployRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.success(nodeService.createNodeAsync(request)));
        } catch (RuntimeException e) {
             if (ResultCode.NODE_LIMIT_EXCEEDED.getMessage().equals(e.getMessage())) {
                 return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                         .body(Response.error(ResultCode.NODE_LIMIT_EXCEEDED));
             }
             return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                     .body(Response.error(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
        }
    }

    @GetMapping("/config/nodes/deploy/{taskId}")
    public ResponseEntity<Response<NodeDeployTaskStatus>> getDeployTaskStatus(@PathVariable String taskId) {
        NodeDeployTaskStatus taskStatus = nodeService.getDeployTaskStatus(taskId);
        if (taskStatus == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Response.error(ResultCode.NOT_FOUND.getCode(), "部署任务不存在"));
        }
        return ResponseEntity.ok(Response.success(taskStatus));
    }

    @GetMapping("/config/nodes/{id}")
    public ResponseEntity<Response<Node>> getNodeById(@PathVariable Integer id) {
        Node node = nodeService.getNodeById(id);
        if (node != null) {
            return ResponseEntity.ok(Response.success(node));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(ResultCode.NODE_NOT_FOUND));
        }
    }

    @PutMapping("/config/nodes/{id}")
    public ResponseEntity<Response<Node>> updateNode(@PathVariable Integer id, @ModelAttribute Node node) {
        Node updatedNode = nodeService.updateNode(id, node);
        if (updatedNode != null) {
            return ResponseEntity.ok(Response.success(updatedNode));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(ResultCode.NODE_NOT_FOUND));
        }
    }

    @DeleteMapping("/config/nodes/{id}")
    public ResponseEntity<Response<Void>> deleteNode(@PathVariable Integer id) {
        boolean deleted = nodeService.deleteNode(id);
        if (deleted) {
            return ResponseEntity.ok(Response.success());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(ResultCode.NODE_NOT_FOUND));
        }
    }
}
