package com.storage.engine.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.Node;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import com.storage.engine.model.Policy;
import com.storage.engine.model.RestfulApiItem;
import com.storage.engine.model.User;
import com.storage.engine.service.AccessService;
import com.storage.engine.service.GrpcApiCatalogService;
import com.storage.engine.service.MetadataKnowledgeService;
import com.storage.engine.service.NodeService;
import com.storage.engine.service.PolicyService;
import com.storage.engine.service.RestfulApiService;
import com.storage.engine.service.StorageService;
import com.storage.engine.service.UserService;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class ScaleStoreGrpcEndpoint extends ScaleStoreGrpcServiceGrpc.ScaleStoreGrpcServiceImplBase {

    @Autowired
    private NodeService nodeService;

    @Autowired
    private UserService userService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private MetadataKnowledgeService metadataKnowledgeService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private AccessService accessService;

    @Autowired
    private RestfulApiService restfulApiService;

    @Autowired
    private GrpcApiCatalogService grpcApiCatalogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void listNodes(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            sendJson(responseObserver, 200, "Success", nodeService.getAllNodes());
        } catch (Exception e) {
            sendError(responseObserver, 500, "List nodes failed: " + safeMessage(e));
        }
    }

    @Override
    public void createNode(NodeCreateRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            NodeDeployRequest payload = new NodeDeployRequest();
            if (request.getId() > 0) {
                payload.setId(request.getId());
            }
            payload.setName(request.getName());
            payload.setIp(request.getIp());
            payload.setPort(request.getPort());
            payload.setDescription(request.getDescription());
            payload.setSshUsername(request.getSshUsername());
            payload.setSshPassword(request.getSshPassword());
            payload.setDeployDirectory(request.getDeployDirectory());
            payload.setPackagePath(request.getPackagePath());
            payload.setZookeeperConnectionString(request.getZookeeperConnectionString());
            payload.setPythonCmd(request.getPythonCmd());

            NodeDeployTaskStatus status = nodeService.createNodeAsync(payload);
            sendJson(responseObserver, 200, "Success", status);
        } catch (RuntimeException e) {
            sendError(responseObserver, 400, safeMessage(e));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Create node failed: " + safeMessage(e));
        }
    }

    @Override
    public void getNode(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }
            Node node = nodeService.getNodeById(request.getId());
            if (node == null) {
                sendError(responseObserver, 404, "Node not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", node);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get node failed: " + safeMessage(e));
        }
    }

    @Override
    public void updateNode(NodeUpdateRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }
            String name = safe(request.getName());
            if (name.isEmpty()) {
                sendError(responseObserver, 400, "name is required");
                return;
            }
            Node node = nodeService.updateNode(request.getId(), name, request.getDescription());
            if (node == null) {
                sendError(responseObserver, 404, "Node not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", node);
        } catch (RuntimeException e) {
            sendError(responseObserver, 400, safeMessage(e));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Update node failed: " + safeMessage(e));
        }
    }

    @Override
    public void removeNode(NodeStopRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }
            NodeDeployTaskStatus status = nodeService.deleteNodeAsync(
                    request.getId(),
                    request.getSshUsername(),
                    request.getSshPort(),
                    request.getSshPassword(),
                    request.getDeployDirectory());
            sendJson(responseObserver, 200, "Success", status);
        } catch (RuntimeException e) {
            if (safeMessage(e).contains("不存在")) {
                sendError(responseObserver, 404, safeMessage(e));
            } else {
                sendError(responseObserver, 400, safeMessage(e));
            }
        } catch (Exception e) {
            sendError(responseObserver, 500, "Remove node failed: " + safeMessage(e));
        }
    }

    @Override
    public void listUsers(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            List<User> users = userService.sanitizeUsers(userService.getAllUsers());
            sendJson(responseObserver, 200, "Success", users);
        } catch (Exception e) {
            sendError(responseObserver, 500, "List users failed: " + safeMessage(e));
        }
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            User payload = new User();
            payload.setUsername(request.getUsername());
            payload.setPassword(request.getPassword());
            payload.setType(request.getType());
            payload.setEmail(request.getEmail());
            payload.setPhone(request.getPhone());
            User created = userService.createUser(payload);
            sendJson(responseObserver, 200, "Success", userService.sanitizeUser(created));
        } catch (RuntimeException e) {
            sendError(responseObserver, 400, safeMessage(e));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Create user failed: " + safeMessage(e));
        }
    }

    @Override
    public void getUser(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }
            User user = userService.getUserById(request.getId());
            if (user == null) {
                sendError(responseObserver, 404, "User not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", userService.sanitizeUser(user));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get user failed: " + safeMessage(e));
        }
    }

    @Override
    public void updateUser(UpdateUserRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }

            User patch = new User();
            if (request.hasUsername()) {
                patch.setUsername(request.getUsername());
            }
            if (request.hasPassword()) {
                patch.setPassword(request.getPassword());
            }
            if (request.hasType()) {
                patch.setType(request.getType());
            }
            if (request.hasEmail()) {
                patch.setEmail(request.getEmail());
            }
            if (request.hasPhone()) {
                patch.setPhone(request.getPhone());
            }

            User updated = userService.updateUser(request.getId(), patch);
            if (updated == null) {
                sendError(responseObserver, 404, "User not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", userService.sanitizeUser(updated));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Update user failed: " + safeMessage(e));
        }
    }

    @Override
    public void deleteUser(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getId() <= 0) {
                sendError(responseObserver, 400, "id must be positive");
                return;
            }
            boolean deleted = userService.deleteUser(request.getId());
            if (!deleted) {
                sendError(responseObserver, 404, "User not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", null);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Delete user failed: " + safeMessage(e));
        }
    }

    @Override
    public void login(UserLoginRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            User user = userService.login(request.getUsername(), request.getPassword());
            if (user == null) {
                sendError(responseObserver, 401, "Invalid username or password");
                return;
            }
            sendJson(responseObserver, 200, "Success", userService.sanitizeUser(user));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Login failed: " + safeMessage(e));
        }
    }

    @Override
    public void getPolicies(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            sendJson(responseObserver, 200, "Success", policyService.getPolicy());
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get policies failed: " + safeMessage(e));
        }
    }

    @Override
    public void updatePolicies(PolicyUpdateRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            Policy patch = new Policy();
            if (request.hasExtractionEnabled()) {
                patch.setExtractionEnabled(request.getExtractionEnabled());
            }
            if (request.hasExtractionScanIntervalMs()) {
                patch.setExtractionScanIntervalMs(request.getExtractionScanIntervalMs());
            }
            sendJson(responseObserver, 200, "Success", policyService.updatePolicy(patch));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Update policies failed: " + safeMessage(e));
        }
    }

    @Override
    public void getMetadataGraph(MetadataGraphRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            int limit = request.getLimit() > 0 ? request.getLimit() : 300;
            Map<String, Object> graph = metadataKnowledgeService.getGraph(emptyToNull(request.getLogicalPath()), limit);
            sendJson(responseObserver, 200, "Success", graph);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get metadata graph failed: " + safeMessage(e));
        }
    }

    @Override
    public void queryMetadata(MetadataQueryRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            Map<String, Object> graph = metadataKnowledgeService.queryBySystemFilters(
                    emptyToNull(request.getLogicalPath()),
                    emptyToNull(request.getDataType()),
                    emptyToNull(request.getKeyword()));
            sendJson(responseObserver, 200, "Success", graph);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Query metadata failed: " + safeMessage(e));
        }
    }

    @Override
    public void uploadData(StorageUploadRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            if (request.getFileContent().isEmpty()) {
                sendError(responseObserver, 400, "file_content is empty");
                return;
            }
            MultipartFile file = new InMemoryMultipartFile(
                    "file",
                    safe(request.getFileName()).isEmpty() ? "upload.bin" : request.getFileName(),
                    "application/octet-stream",
                    request.getFileContent().toByteArray());
            DataItem item = storageService.storeData(file, request.getLogicalPath(), request.getDataType());
            sendJson(responseObserver, 200, "Success", item);
        } catch (IllegalArgumentException e) {
            sendError(responseObserver, 400, safeMessage(e));
        } catch (Exception e) {
            sendError(responseObserver, 500, "Upload data failed: " + safeMessage(e));
        }
    }

    @Override
    public void accessData(LogicalPathRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            DataItem item = accessService.accessData(request.getLogicalPath());
            if (item == null) {
                sendError(responseObserver, 404, "Data not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", item);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Access data failed: " + safeMessage(e));
        }
    }

    @Override
    public void downloadData(LogicalPathRequest request, StreamObserver<DownloadDataResponse> responseObserver) {
        try {
            DataItem meta = accessService.getMetaByAccessPath(request.getLogicalPath());
            if (meta == null) {
                responseObserver.onNext(DownloadDataResponse.newBuilder()
                        .setCode(404)
                        .setMessage("Data not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            byte[] data = accessService.downloadData(request.getLogicalPath());
            if (data == null) {
                data = new byte[0];
            }

            responseObserver.onNext(DownloadDataResponse.newBuilder()
                    .setCode(200)
                    .setMessage("Success")
                    .setFileName(safe(meta.getFileName()))
                    .setDataType(safe(meta.getDataType()))
                    .setFileFormat(safe(meta.getFileFormat()))
                    .setFileSize(meta.getFileSize() == null ? 0L : meta.getFileSize())
                    .setFileContent(ByteString.copyFrom(data))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(DownloadDataResponse.newBuilder()
                    .setCode(500)
                    .setMessage("Download data failed: " + safeMessage(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void listRestfulInterfaces(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            sendJson(responseObserver, 200, "Success", restfulApiService.getAllRestfulApis());
        } catch (Exception e) {
            sendError(responseObserver, 500, "List RESTful interfaces failed: " + safeMessage(e));
        }
    }

    @Override
    public void getRestfulInterface(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            RestfulApiItem item = restfulApiService.getRestfulApiById(request.getId());
            if (item == null) {
                sendError(responseObserver, 404, "RESTful API not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", item);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get RESTful interface failed: " + safeMessage(e));
        }
    }

    @Override
    public void listJavaGrpcInterfacesCatalog(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            sendJson(responseObserver, 200, "Success", grpcApiCatalogService.getAllJavaGrpcApis());
        } catch (Exception e) {
            sendError(responseObserver, 500, "List Java gRPC interfaces failed: " + safeMessage(e));
        }
    }

    @Override
    public void getJavaGrpcInterfaceCatalog(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            RestfulApiItem item = grpcApiCatalogService.getJavaGrpcApiById(request.getId());
            if (item == null) {
                sendError(responseObserver, 404, "Java gRPC API not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", item);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get Java gRPC interface failed: " + safeMessage(e));
        }
    }

    @Override
    public void listPythonGrpcInterfacesCatalog(EmptyRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            sendJson(responseObserver, 200, "Success", grpcApiCatalogService.getAllPythonGrpcApis());
        } catch (Exception e) {
            sendError(responseObserver, 500, "List Python gRPC interfaces failed: " + safeMessage(e));
        }
    }

    @Override
    public void getPythonGrpcInterfaceCatalog(IdRequest request, StreamObserver<JsonResponse> responseObserver) {
        try {
            RestfulApiItem item = grpcApiCatalogService.getPythonGrpcApiById(request.getId());
            if (item == null) {
                sendError(responseObserver, 404, "Python gRPC API not found");
                return;
            }
            sendJson(responseObserver, 200, "Success", item);
        } catch (Exception e) {
            sendError(responseObserver, 500, "Get Python gRPC interface failed: " + safeMessage(e));
        }
    }

    private void sendJson(StreamObserver<JsonResponse> responseObserver, int code, String message, Object data) {
        responseObserver.onNext(JsonResponse.newBuilder()
                .setCode(code)
                .setMessage(safe(message))
                .setJsonData(toJson(data))
                .build());
        responseObserver.onCompleted();
    }

    private void sendError(StreamObserver<JsonResponse> responseObserver, int code, String message) {
        sendJson(responseObserver, code, message, Collections.emptyMap());
    }

    private String toJson(Object data) {
        try {
            return data == null ? "null" : objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safeMessage(Throwable t) {
        if (t == null || t.getMessage() == null || t.getMessage().trim().isEmpty()) {
            return "Unexpected error";
        }
        return t.getMessage().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
