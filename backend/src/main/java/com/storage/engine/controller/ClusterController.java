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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                    .body(Response.error(ResultCode.NOT_FOUND.getCode(), "任务不存在"));
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
    public ResponseEntity<Response<Node>> updateNode(@PathVariable Integer id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String description = body.get("description");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Response.error(ResultCode.PARAM_ERROR.getCode(), "节点名不能为空"));
        }
        Node updatedNode = nodeService.updateNode(id, name.trim(), description != null ? description.trim() : "");
        if (updatedNode != null) {
            return ResponseEntity.ok(Response.success(updatedNode));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(ResultCode.NODE_NOT_FOUND));
        }
    }

    @PostMapping("/config/nodes/{id}/stop")
    public ResponseEntity<Response<NodeDeployTaskStatus>> stopNode(@PathVariable Integer id,
                @RequestBody NodeDeployRequest request) {
        try {
            NodeDeployTaskStatus taskStatus = nodeService.deleteNodeAsync(id,
                    request.getSshUsername(), request.getSshPort(), request.getSshPassword(), request.getDeployDirectory());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.success(taskStatus));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("节点不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error(ResultCode.NODE_NOT_FOUND));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Response.error(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
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

    @GetMapping("/config/server-info")
    public ResponseEntity<Response<Map<String, String>>> getServerInfo() {
        Map<String, String> info = new HashMap<String, String>();
        info.put("ip", detectServerIp());
        return ResponseEntity.ok(Response.success(info));
    }

    private String detectServerIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return "127.0.0.1";
    }
}
