package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.RestfulApiItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GrpcApiCatalogService {

    private static final String LANG_JAVA = "java";
    private static final String LANG_PYTHON = "python";

    private static final Map<String, String> COMMON_RPC_BY_ROUTE = new HashMap<String, String>();

    static {
        COMMON_RPC_BY_ROUTE.put("GET /config/nodes", "ScaleStoreGrpcService/ListNodes");
        COMMON_RPC_BY_ROUTE.put("POST /config/nodes", "ScaleStoreGrpcService/CreateNode");
        COMMON_RPC_BY_ROUTE.put("GET /config/nodes/{id}", "ScaleStoreGrpcService/GetNode");
        COMMON_RPC_BY_ROUTE.put("PUT /config/nodes/{id}", "ScaleStoreGrpcService/UpdateNode");
        COMMON_RPC_BY_ROUTE.put("POST /config/nodes/{id}/stop", "ScaleStoreGrpcService/RemoveNode");

        COMMON_RPC_BY_ROUTE.put("GET /config/users", "ScaleStoreGrpcService/ListUsers");
        COMMON_RPC_BY_ROUTE.put("POST /config/users", "ScaleStoreGrpcService/CreateUser");
        COMMON_RPC_BY_ROUTE.put("GET /config/users/{id}", "ScaleStoreGrpcService/GetUser");
        COMMON_RPC_BY_ROUTE.put("PUT /config/users/{id}", "ScaleStoreGrpcService/UpdateUser");
        COMMON_RPC_BY_ROUTE.put("DELETE /config/users/{id}", "ScaleStoreGrpcService/DeleteUser");
        COMMON_RPC_BY_ROUTE.put("POST /config/login", "ScaleStoreGrpcService/Login");

        COMMON_RPC_BY_ROUTE.put("GET /config/policies", "ScaleStoreGrpcService/GetPolicies");
        COMMON_RPC_BY_ROUTE.put("PUT /config/policies", "ScaleStoreGrpcService/UpdatePolicies");

        COMMON_RPC_BY_ROUTE.put("GET /metadata/graph", "ScaleStoreGrpcService/GetMetadataGraph");
        COMMON_RPC_BY_ROUTE.put("GET /metadata/query", "ScaleStoreGrpcService/QueryMetadata");

        COMMON_RPC_BY_ROUTE.put("POST /storage", "ScaleStoreGrpcService/UploadData");
        COMMON_RPC_BY_ROUTE.put("GET /access/data", "ScaleStoreGrpcService/AccessData");
        COMMON_RPC_BY_ROUTE.put("GET /access/download", "ScaleStoreGrpcService/DownloadData");
    }

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private RestfulApiService restfulApiService;

    private final Object seedLock = new Object();

    public List<RestfulApiItem> getAllJavaGrpcApis() {
        ensureSeeded(LANG_JAVA);
        return parseItems(iginxDao.getAllJavaGrpcApis());
    }

    public RestfulApiItem getJavaGrpcApiById(Integer id) {
        if (id == null) {
            return null;
        }

        ensureSeeded(LANG_JAVA);
        List<RestfulApiItem> items = parseItems(iginxDao.getJavaGrpcApiById(id));
        return items.isEmpty() ? null : items.get(0);
    }

    public List<RestfulApiItem> getAllPythonGrpcApis() {
        ensureSeeded(LANG_PYTHON);
        return parseItems(iginxDao.getAllPythonGrpcApis());
    }

    public RestfulApiItem getPythonGrpcApiById(Integer id) {
        if (id == null) {
            return null;
        }

        ensureSeeded(LANG_PYTHON);
        List<RestfulApiItem> items = parseItems(iginxDao.getPythonGrpcApiById(id));
        return items.isEmpty() ? null : items.get(0);
    }

    private void ensureSeeded(String language) {
        if (!parseItems(queryByLanguage(language)).isEmpty()) {
            return;
        }

        synchronized (seedLock) {
            if (!parseItems(queryByLanguage(language)).isEmpty()) {
                return;
            }

            List<RestfulApiItem> defaults = buildDefaultCatalog(language);
            for (RestfulApiItem item : defaults) {
                insertByLanguage(language, item);
            }
        }
    }

    private SessionExecuteSqlResult queryByLanguage(String language) {
        if (LANG_JAVA.equalsIgnoreCase(language)) {
            return iginxDao.getAllJavaGrpcApis();
        }
        return iginxDao.getAllPythonGrpcApis();
    }

    private void insertByLanguage(String language, RestfulApiItem item) {
        if (item == null || item.getId() == null) {
            return;
        }

        if (LANG_JAVA.equalsIgnoreCase(language)) {
            iginxDao.insertJavaGrpcApi(
                    item.getId(),
                    safe(item.getName()),
                    safe(item.getUrl()),
                    safe(item.getMethod()),
                    safe(item.getDescription()),
                    safe(item.getParamsExample()),
                    safe(item.getResponseExample()),
                    safe(item.getCurlExample()),
                    true
            );
            return;
        }

        iginxDao.insertPythonGrpcApi(
                item.getId(),
                safe(item.getName()),
                safe(item.getUrl()),
                safe(item.getMethod()),
                safe(item.getDescription()),
                safe(item.getParamsExample()),
                safe(item.getResponseExample()),
                safe(item.getCurlExample()),
                true
        );
    }

    private List<RestfulApiItem> buildDefaultCatalog(String language) {
        List<RestfulApiItem> out = new ArrayList<RestfulApiItem>();
        List<RestfulApiItem> restfulItems = restfulApiService.getAllRestfulApis();
        for (RestfulApiItem restItem : restfulItems) {
            if (restItem == null || restItem.getId() == null) {
                continue;
            }

            String rpcName = resolveRpcName(restItem, language);
            RestfulApiItem grpcItem = new RestfulApiItem();
            grpcItem.setId(restItem.getId());
            grpcItem.setName(safe(restItem.getName()));
            grpcItem.setUrl(rpcName);
            grpcItem.setMethod("UNARY");
            grpcItem.setDescription(safe(restItem.getDescription()));
            grpcItem.setParamsExample(safe(restItem.getParamsExample()));
            grpcItem.setResponseExample(safe(restItem.getResponseExample()));
            grpcItem.setCurlExample(buildInvokeExample(language, rpcName));
            grpcItem.setIsValid(true);
            out.add(grpcItem);
        }
        return out;
    }

    private String resolveRpcName(RestfulApiItem restItem, String language) {
        String method = normalizeMethod(restItem.getMethod());
        String url = safe(restItem.getUrl());

        if ("/config/interfaces/restful".equals(url)) {
            return "ScaleStoreGrpcService/ListRestfulInterfaces";
        }

        if ("/config/interfaces/restful/{id}".equals(url)) {
            return "ScaleStoreGrpcService/GetRestfulInterface";
        }

        String key = method + " " + url;
        String rpcName = COMMON_RPC_BY_ROUTE.get(key);
        if (rpcName != null) {
            return rpcName;
        }

        return "GatewayService/Invoke";
    }

    private String buildInvokeExample(String language, String rpcName) {
        String methodName = extractRpcMethodName(rpcName);
        String requestType = methodName + "Request";

        if (LANG_JAVA.equalsIgnoreCase(language)) {
            return "stub." + lowerFirst(methodName) + "(" + requestType + ".newBuilder().build());";
        }

        return "response = stub." + methodName + "(" + requestType + "())";
    }

    private String extractRpcMethodName(String rpcName) {
        String safeRpc = safe(rpcName);
        int slash = safeRpc.lastIndexOf('/');
        if (slash == -1 || slash == safeRpc.length() - 1) {
            return "Invoke";
        }
        return safeRpc.substring(slash + 1);
    }

    private String lowerFirst(String value) {
        String text = safe(value);
        if (text.isEmpty()) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private List<RestfulApiItem> parseItems(SessionExecuteSqlResult result) {
        List<RestfulApiItem> out = new ArrayList<RestfulApiItem>();
        if (result == null || result.getKeys() == null || result.getValues() == null || result.getPaths() == null) {
            return out;
        }

        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        int nameIdx = -1;
        int urlIdx = -1;
        int methodIdx = -1;
        int descIdx = -1;
        int paramsIdx = -1;
        int responseIdx = -1;
        int invokeIdx = -1;
        int validIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("name")) {
                nameIdx = i;
            } else if (path.endsWith("url")) {
                urlIdx = i;
            } else if (path.endsWith("method")) {
                methodIdx = i;
            } else if (path.endsWith("description")) {
                descIdx = i;
            } else if (path.endsWith("paramsExample")) {
                paramsIdx = i;
            } else if (path.endsWith("responseExample")) {
                responseIdx = i;
            } else if (path.endsWith("curlExample")) {
                invokeIdx = i;
            } else if (path.endsWith("isValid")) {
                validIdx = i;
            }
        }

        for (int i = 0; i < keys.length; i++) {
            List<Object> row = values.get(i);
            if (row == null) {
                continue;
            }

            boolean isValid = true;
            if (validIdx != -1 && validIdx < row.size()) {
                Boolean v = getValueAsBoolean(row.get(validIdx));
                isValid = v != null ? v : true;
            }
            if (!isValid) {
                continue;
            }

            RestfulApiItem item = new RestfulApiItem();
            item.setId((int) keys[i]);
            item.setName(nameIdx == -1 || nameIdx >= row.size() ? "" : getValueAsString(row.get(nameIdx)));
            item.setUrl(urlIdx == -1 || urlIdx >= row.size() ? "" : getValueAsString(row.get(urlIdx)));
            item.setMethod(methodIdx == -1 || methodIdx >= row.size() ? "" : normalizeMethod(getValueAsString(row.get(methodIdx))));
            item.setDescription(descIdx == -1 || descIdx >= row.size() ? "" : getValueAsString(row.get(descIdx)));
            item.setParamsExample(paramsIdx == -1 || paramsIdx >= row.size() ? "" : getValueAsString(row.get(paramsIdx)));
            item.setResponseExample(responseIdx == -1 || responseIdx >= row.size() ? "" : getValueAsString(row.get(responseIdx)));
            item.setCurlExample(invokeIdx == -1 || invokeIdx >= row.size() ? "" : getValueAsString(row.get(invokeIdx)));
            item.setIsValid(true);
            out.add(item);
        }

        return out;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeMethod(String method) {
        return safe(method).toUpperCase(Locale.ROOT);
    }

    private String getValueAsString(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        }
        return obj.toString();
    }

    private Boolean getValueAsBoolean(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Boolean) {
            return (Boolean) obj;
        }
        if (obj instanceof byte[]) {
            String raw = new String((byte[]) obj, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(raw) || "false".equals(raw)) {
                return Boolean.parseBoolean(raw);
            }
        }
        return null;
    }
}
