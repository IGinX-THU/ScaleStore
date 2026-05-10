package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.DataItem;
import com.storage.engine.model.DataSourceInfo;
import com.storage.engine.model.DataSourceSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataSourceService {

    private static final String DEFAULT_SOURCE_GROUP = "default";
    private static final String DEFAULT_SOURCE_NAME = "平台默认数据源";

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private AccessService accessService;

    public synchronized void ensureDefaultDataSourceInitialized() {
        List<DataSourceInfo> existing = getPersistedDataSources();
        for (DataSourceInfo info : existing) {
            if (Boolean.TRUE.equals(info.getIsDefault())) {
                return;
            }
        }
        upsertDefaultDataSource(calculateDefaultDataSize());
    }

    public synchronized void registerExternalDataSource(String ip, int port, String type, String schemaPrefix, String dataPrefix, long dataSize) {
        ensureDefaultDataSourceInitialized();
        String normalizedSchemaPrefix = safe(schemaPrefix);
        List<DataSourceInfo> existing = getPersistedDataSources();
        DataSourceInfo matched = null;
        for (DataSourceInfo info : existing) {
            if (!normalizedSchemaPrefix.isEmpty() && normalizedSchemaPrefix.equals(safe(info.getSchemaPrefix()))) {
                matched = info;
                break;
            }
            if (normalizedSchemaPrefix.isEmpty() && !Boolean.TRUE.equals(info.getIsDefault())
                    && safe(info.getIp()).equals(safe(ip))
                    && safe(info.getPort()).equals(String.valueOf(port))) {
                matched = info;
                break;
            }
        }

        long key = matched != null && matched.getId() != null ? matched.getId().longValue() : iginxDao.getMaxDataSourceId() + 1;
        String sourceType = safe(type).isEmpty() ? "unknown" : safe(type).toLowerCase(Locale.ROOT);
        String sourceName = buildExternalSourceName(sourceType, ip, port);
        iginxDao.insertDataSource(key, sourceName, safe(ip), String.valueOf(port), sourceType, normalizedSchemaPrefix, safe(dataPrefix), true, false, Math.max(0L, dataSize), normalizedSchemaPrefix.isEmpty() ? sourceName : normalizedSchemaPrefix, true);
    }

    public synchronized void increaseExternalDataSourceSize(String schemaPrefix, long delta) {
        String normalizedSchemaPrefix = safe(schemaPrefix);
        if (normalizedSchemaPrefix.isEmpty() || delta <= 0L) {
            return;
        }

        ensureDefaultDataSourceInitialized();
        List<DataSourceInfo> existing = getPersistedDataSources();
        DataSourceInfo matched = null;
        for (DataSourceInfo info : existing) {
            if (info == null || Boolean.TRUE.equals(info.getIsDefault())) {
                continue;
            }
            if (normalizedSchemaPrefix.equals(safe(info.getSchemaPrefix()))) {
                matched = info;
                break;
            }
        }

        if (matched == null) {
            return;
        }

        long current = matched.getDataSize() == null ? 0L : Math.max(0L, matched.getDataSize().longValue());
        long target = current + Math.max(0L, delta);

        long key = matched.getId() == null ? iginxDao.getMaxDataSourceId() + 1 : matched.getId().longValue();
        String sourceType = safe(matched.getType()).isEmpty() ? "unknown" : safe(matched.getType()).toLowerCase(Locale.ROOT);
        String sourceName = safe(matched.getName()).isEmpty()
                ? buildExternalSourceName(sourceType, safe(matched.getIp()), parsePort(safe(matched.getPort())))
                : safe(matched.getName());

        iginxDao.insertDataSource(
                key,
                sourceName,
                safe(matched.getIp()),
                safe(matched.getPort()),
                sourceType,
                normalizedSchemaPrefix,
                safe(matched.getDataPrefix()),
                true,
                false,
                target,
                safe(matched.getSourceGroup()).isEmpty() ? normalizedSchemaPrefix : safe(matched.getSourceGroup()),
                true);
    }

    public synchronized void increaseDefaultDataSourceSize(long delta) {
        ensureDefaultDataSourceInitialized();
        List<DataSourceInfo> existing = getPersistedDataSources();
        DataSourceInfo target = null;
        for (DataSourceInfo info : existing) {
            if (Boolean.TRUE.equals(info.getIsDefault())) {
                target = info;
                break;
            }
        }
        long current = target != null && target.getDataSize() != null ? target.getDataSize().longValue() : calculateDefaultDataSize();
        upsertDefaultDataSource(current + Math.max(0L, delta));
    }

    public synchronized DataSourceSummary getDataSourceSummary() {
        ensureDefaultDataSourceInitialized();
        List<DataSourceInfo> persisted = getPersistedDataSources();
        List<DataSourceInfo> merged = mergeWithClusterInfo(persisted, null);
        long total = 0L;
        for (DataSourceInfo info : merged) {
            if (info != null && info.getDataSize() != null) {
                total += Math.max(0L, info.getDataSize().longValue());
            }
        }
        DataSourceSummary summary = new DataSourceSummary();
        summary.setTotalDataSize(total);
        summary.setDataSources(merged);
        return summary;
    }

    private void upsertDefaultDataSource(long dataSize) {
        List<DataSourceInfo> persisted = getPersistedDataSources();
        DataSourceInfo existing = null;
        for (DataSourceInfo info : persisted) {
            if (Boolean.TRUE.equals(info.getIsDefault())) {
                existing = info;
                break;
            }
        }
        long key = existing != null && existing.getId() != null ? existing.getId().longValue() : iginxDao.getMaxDataSourceId() + 1;
        iginxDao.insertDataSource(key, DEFAULT_SOURCE_NAME, "system", "default", "filesystem", "", "", true, true, Math.max(0L, dataSize), DEFAULT_SOURCE_GROUP, true);
    }

    private List<DataSourceInfo> mergeWithClusterInfo(List<DataSourceInfo> persisted, Object ignoredClusterInfo) {
        Map<String, DataSourceInfo> persistedBySchema = new LinkedHashMap<String, DataSourceInfo>();
        DataSourceInfo defaultInfo = null;
        for (DataSourceInfo info : persisted) {
            if (info == null) {
                continue;
            }
            if (Boolean.TRUE.equals(info.getIsDefault())) {
                defaultInfo = info;
            }
            String schemaPrefix = safe(info.getSchemaPrefix());
            if (!schemaPrefix.isEmpty()) {
                persistedBySchema.put(schemaPrefix, info);
            }
        }

        List<DataSourceInfo> result = new ArrayList<DataSourceInfo>();
        if (defaultInfo != null) {
            result.add(defaultInfo);
        }
        for (DataSourceInfo info : persisted) {
            if (info == null || Boolean.TRUE.equals(info.getIsDefault())) {
                continue;
            }
            result.add(cloneInfo(info));
        }
        return result;
    }

    private DataSourceInfo cloneInfo(DataSourceInfo source) {
        DataSourceInfo item = new DataSourceInfo();
        item.setId(source.getId());
        item.setName(source.getName());
        item.setIp(source.getIp());
        item.setPort(source.getPort());
        item.setType(source.getType());
        item.setSchemaPrefix(source.getSchemaPrefix());
        item.setDataPrefix(source.getDataPrefix());
        item.setConnected(source.getConnected());
        item.setIsDefault(source.getIsDefault());
        item.setDataSize(source.getDataSize());
        item.setSourceGroup(source.getSourceGroup());
        return item;
    }

    private long calculateDefaultDataSize() {
        List<DataItem> allMeta = accessService.getAllMeta();
        long total = 0L;
        for (DataItem item : allMeta) {
            if (item == null || item.getFileSize() == null) {
                continue;
            }
            String logicalPath = safe(item.getLogicalPath());
            if (logicalPath.startsWith("/extern/")) {
                continue;
            }
            total += Math.max(0L, item.getFileSize().longValue());
        }
        return total;
    }

    private List<DataSourceInfo> getPersistedDataSources() {
        SessionExecuteSqlResult result = iginxDao.getAllDataSources();
        if (result == null) {
            return new ArrayList<DataSourceInfo>();
        }
        List<DataSourceInfo> list = new ArrayList<DataSourceInfo>();
        long[] keys = result.getKeys();
        List<String> paths = result.getPaths();
        List<List<Object>> values = result.getValues();
        if (keys == null || paths == null || values == null) {
            return list;
        }
        int nameIdx = -1, ipIdx = -1, portIdx = -1, typeIdx = -1, schemaIdx = -1, dataPrefixIdx = -1, connectedIdx = -1, defaultIdx = -1, sizeIdx = -1, groupIdx = -1, validIdx = -1;
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("name")) nameIdx = i;
            else if (path.endsWith("ip")) ipIdx = i;
            else if (path.endsWith("port")) portIdx = i;
            else if (path.endsWith("type")) typeIdx = i;
            else if (path.endsWith("schemaPrefix")) schemaIdx = i;
            else if (path.endsWith("dataPrefix")) dataPrefixIdx = i;
            else if (path.endsWith("connected")) connectedIdx = i;
            else if (path.endsWith("isDefault")) defaultIdx = i;
            else if (path.endsWith("dataSize")) sizeIdx = i;
            else if (path.endsWith("sourceGroup")) groupIdx = i;
            else if (path.endsWith("isValid")) validIdx = i;
        }
        for (int i = 0; i < keys.length; i++) {
            List<Object> row = values.get(i);
            if (row == null) {
                continue;
            }
            boolean isValid = validIdx < 0 || valueAsBoolean(row.get(validIdx));
            if (!isValid) {
                continue;
            }
            DataSourceInfo item = new DataSourceInfo();
            item.setId((int) keys[i]);
            if (nameIdx >= 0) item.setName(valueAsString(row.get(nameIdx)));
            if (ipIdx >= 0) item.setIp(valueAsString(row.get(ipIdx)));
            if (portIdx >= 0) item.setPort(valueAsString(row.get(portIdx)));
            if (typeIdx >= 0) item.setType(valueAsString(row.get(typeIdx)));
            if (schemaIdx >= 0) item.setSchemaPrefix(valueAsString(row.get(schemaIdx)));
            if (dataPrefixIdx >= 0) item.setDataPrefix(valueAsString(row.get(dataPrefixIdx)));
            if (connectedIdx >= 0) item.setConnected(valueAsBoolean(row.get(connectedIdx)));
            if (defaultIdx >= 0) item.setIsDefault(valueAsBoolean(row.get(defaultIdx)));
            if (sizeIdx >= 0) item.setDataSize(valueAsLong(row.get(sizeIdx)));
            if (groupIdx >= 0) item.setSourceGroup(valueAsString(row.get(groupIdx)));
            list.add(item);
        }
        return list;
    }

    private String buildExternalSourceName(String type, String ip, int port) {
        return safe(type).toLowerCase(Locale.ROOT) + "-" + safe(ip) + ":" + port;
    }

    private int parsePort(String text) {
        try {
            return Integer.parseInt(safe(text));
        } catch (Exception e) {
            return 0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private boolean valueAsBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Boolean.parseBoolean(valueAsString(value));
    }

    private Long valueAsLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(valueAsString(value));
        } catch (Exception e) {
            return 0L;
        }
    }
}
