package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.RestfulApiItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RestfulApiService {

    private static final Set<String> HIDDEN_INTERFACE_NAMES = new HashSet<String>(Arrays.asList(
        "查询节点部署任务状态",
        "删除节点元数据",
        "查询服务端信息",
        "查询元数据抽取事件",
        "查询数据清单"
    ));

    private static final Set<String> REST_META_ALLOWED_NAMES = new HashSet<String>(Arrays.asList(
        "查询RESTful接口列表",
        "查询RESTful接口详情"
    ));

    @Autowired
    private IGinxDao iginxDao;

    public List<RestfulApiItem> getAllRestfulApis() {
        try {
            SessionExecuteSqlResult result = iginxDao.getAllRestfulApis();
            return normalizeVisibleItems(parseItems(result));
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<RestfulApiItem>();
        }
    }

    public RestfulApiItem getRestfulApiById(Integer id) {
        if (id == null) {
            return null;
        }

        try {
            SessionExecuteSqlResult result = iginxDao.getRestfulApiById(id);
            List<RestfulApiItem> items = parseItems(result);
            if (items.isEmpty()) {
                return null;
            }
            return normalizeVisibleItem(items.get(0));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized RestfulApiItem createRestfulApi(RestfulApiItem item) {
        validateForCreate(item);

        int id = item.getId() != null ? item.getId() : (int) (iginxDao.getMaxRestfulApiId() + 1);
        String method = normalizeMethod(item.getMethod());

        iginxDao.insertRestfulApi(
                id,
                safe(item.getName()),
                safe(item.getUrl()),
                method,
                safe(item.getDescription()),
                safe(item.getParamsExample()),
                safe(item.getResponseExample()),
                safe(item.getCurlExample()),
                true
        );

        return getRestfulApiById(id);
    }

    public synchronized RestfulApiItem updateRestfulApi(Integer id, RestfulApiItem patch) {
        RestfulApiItem existing = getRestfulApiById(id);
        if (existing == null) {
            return null;
        }
        if (patch == null) {
            throw new RuntimeException("Payload is empty");
        }

        String name = hasText(patch.getName()) ? patch.getName().trim() : existing.getName();
        String url = hasText(patch.getUrl()) ? patch.getUrl().trim() : existing.getUrl();
        String method = hasText(patch.getMethod()) ? normalizeMethod(patch.getMethod()) : existing.getMethod();
        String description = patch.getDescription() != null ? patch.getDescription() : existing.getDescription();
        String paramsExample = patch.getParamsExample() != null ? patch.getParamsExample() : existing.getParamsExample();
        String responseExample = patch.getResponseExample() != null ? patch.getResponseExample() : existing.getResponseExample();
        String curlExample = patch.getCurlExample() != null ? patch.getCurlExample() : existing.getCurlExample();

        if (!hasText(name) || !hasText(url) || !hasText(method)) {
            throw new RuntimeException("name, url, method are required");
        }

        iginxDao.updateRestfulApi(
                id,
                name,
                url,
                method,
                safe(description),
                safe(paramsExample),
                safe(responseExample),
                safe(curlExample),
                true
        );

        return getRestfulApiById(id);
    }

    public synchronized boolean deleteRestfulApi(Integer id) {
        RestfulApiItem existing = getRestfulApiById(id);
        if (existing == null) {
            return false;
        }
        iginxDao.deleteRestfulApi(id);
        return true;
    }

    private void validateForCreate(RestfulApiItem item) {
        if (item == null) {
            throw new RuntimeException("Payload is empty");
        }
        if (!hasText(item.getName()) || !hasText(item.getUrl()) || !hasText(item.getMethod())) {
            throw new RuntimeException("name, url, method are required");
        }
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
        int curlIdx = -1;
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
                curlIdx = i;
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
            if (validIdx != -1) {
                Boolean v = getValueAsBoolean(row.get(validIdx));
                isValid = v != null ? v : true;
            }
            if (!isValid) {
                continue;
            }

            RestfulApiItem item = new RestfulApiItem();
            item.setId((int) keys[i]);
            item.setName(nameIdx == -1 ? "" : getValueAsString(row.get(nameIdx)));
            item.setUrl(urlIdx == -1 ? "" : getValueAsString(row.get(urlIdx)));
            item.setMethod(methodIdx == -1 ? "" : normalizeMethod(getValueAsString(row.get(methodIdx))));
            item.setDescription(descIdx == -1 ? "" : getValueAsString(row.get(descIdx)));
            item.setParamsExample(paramsIdx == -1 ? "" : getValueAsString(row.get(paramsIdx)));
            item.setResponseExample(responseIdx == -1 ? "" : getValueAsString(row.get(responseIdx)));
            item.setCurlExample(curlIdx == -1 ? "" : getValueAsString(row.get(curlIdx)));
            item.setIsValid(true);
            out.add(item);
        }

        return out;
    }

    private List<RestfulApiItem> normalizeVisibleItems(List<RestfulApiItem> items) {
        List<RestfulApiItem> out = new ArrayList<RestfulApiItem>();
        if (items == null) {
            return out;
        }

        for (RestfulApiItem item : items) {
            RestfulApiItem normalized = normalizeVisibleItem(item);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private RestfulApiItem normalizeVisibleItem(RestfulApiItem item) {
        if (item == null) {
            return null;
        }

        String name = safe(item.getName());
        if (name.isEmpty()) {
            return null;
        }

        if (HIDDEN_INTERFACE_NAMES.contains(name)) {
            return null;
        }

        String url = safe(item.getUrl());
        if (url.startsWith("/config/interfaces/restful") && !REST_META_ALLOWED_NAMES.contains(name)) {
            return null;
        }

        if ("停止节点(异步)".equals(name)) {
            item.setName("移除节点(异步)");
            item.setDescription(safe(item.getDescription()).replace("停止指定节点", "移除指定节点"));
        }

        return item;
    }

    private String normalizeMethod(String method) {
        return safe(method).toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
