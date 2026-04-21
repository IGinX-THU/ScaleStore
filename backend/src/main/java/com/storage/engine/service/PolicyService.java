package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.component.MetadataTransformJobInitializer;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Service
public class PolicyService {

    private static final boolean DEFAULT_EXTRACTION_ENABLED = true;
    private static final long DEFAULT_SCAN_INTERVAL_MS = 60000L;

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private Environment environment;

    @Autowired
    private MetadataTransformJobInitializer metadataTransformJobInitializer;

    private volatile Policy cachedPolicy;

    @PostConstruct
    public synchronized void initializePolicyCache() {
        cachedPolicy = loadEffectivePolicy();
    }

    public synchronized Policy getPolicy() {
        if (cachedPolicy == null) {
            cachedPolicy = loadEffectivePolicy();
        }
        return copyPolicy(cachedPolicy);
    }

    public synchronized Policy updatePolicy(Policy policy) {
        Policy current = getPolicy();

        boolean extractionEnabled = current.getExtractionEnabled() != null
                ? current.getExtractionEnabled()
                : DEFAULT_EXTRACTION_ENABLED;
        long scanIntervalMs = current.getExtractionScanIntervalMs() != null
                ? current.getExtractionScanIntervalMs()
                : DEFAULT_SCAN_INTERVAL_MS;

        if (policy != null) {
            if (policy.getExtractionEnabled() != null) {
                extractionEnabled = policy.getExtractionEnabled();
            }
            if (policy.getExtractionScanIntervalMs() != null) {
                scanIntervalMs = sanitizeScanInterval(policy.getExtractionScanIntervalMs());
            }
        }

        boolean oldEnabled = current.getExtractionEnabled() != null
                ? current.getExtractionEnabled()
                : DEFAULT_EXTRACTION_ENABLED;
        long oldIntervalMs = current.getExtractionScanIntervalMs() != null
                ? sanitizeScanInterval(current.getExtractionScanIntervalMs())
                : DEFAULT_SCAN_INTERVAL_MS;
        long newIntervalMs = sanitizeScanInterval(scanIntervalMs);

        iginxDao.updatePolicy(extractionEnabled, newIntervalMs);

        metadataTransformJobInitializer.onPolicyUpdated(
                oldEnabled,
                oldIntervalMs,
                extractionEnabled,
                newIntervalMs);

        cachedPolicy = loadEffectivePolicy();
        return copyPolicy(cachedPolicy);
    }

    private Policy loadEffectivePolicy() {
        Policy policy = buildStartupPolicy();

        try {
            SessionExecuteSqlResult result = iginxDao.getPolicy();
            Policy userOverride = parsePolicy(result);
            if (userOverride != null) {
                if (userOverride.getExtractionEnabled() != null) {
                    policy.setExtractionEnabled(userOverride.getExtractionEnabled());
                    policy.setExtractionEnabledSource("USER_OVERRIDE");
                }
                if (userOverride.getExtractionScanIntervalMs() != null) {
                    policy.setExtractionScanIntervalMs(sanitizeScanInterval(userOverride.getExtractionScanIntervalMs()));
                    policy.setExtractionScanIntervalMsSource("USER_OVERRIDE");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        fillDescriptions(policy);
        return policy;
    }

    private Policy copyPolicy(Policy source) {
        Policy copy = new Policy();
        if (source == null) {
            fillDescriptions(copy);
            return copy;
        }

        copy.setExtractionEnabled(source.getExtractionEnabled());
        copy.setExtractionScanIntervalMs(source.getExtractionScanIntervalMs());
        copy.setExtractionEnabledSource(source.getExtractionEnabledSource());
        copy.setExtractionScanIntervalMsSource(source.getExtractionScanIntervalMsSource());
        copy.setExtractionEnabledDesc(source.getExtractionEnabledDesc());
        copy.setExtractionScanIntervalMsDesc(source.getExtractionScanIntervalMsDesc());
        return copy;
    }

    private Policy parsePolicy(SessionExecuteSqlResult result) {
        if (result == null) {
            return null;
        }

        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        if (values == null || paths == null || values.isEmpty()) {
            return null;
        }

        int extractionEnabledIdx = -1;
        int scanIntervalMsIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("extractionEnabled")) {
                extractionEnabledIdx = i;
            } else if (path.endsWith("extractionScanIntervalMs")) {
                scanIntervalMsIdx = i;
            }
        }

        List<Object> row = values.get(values.size() - 1);
        if (row == null) {
            return null;
        }

        Policy policy = new Policy();
        boolean hasAny = false;

        if (extractionEnabledIdx != -1) {
            Boolean v = getValueAsBoolean(row.get(extractionEnabledIdx));
            if (v != null) {
                policy.setExtractionEnabled(v);
                hasAny = true;
            }
        }

        if (scanIntervalMsIdx != -1) {
            Long v = getValueAsLong(row.get(scanIntervalMsIdx));
            if (v != null) {
                policy.setExtractionScanIntervalMs(v);
                hasAny = true;
            }
        }

        return hasAny ? policy : null;
    }

    private Policy buildStartupPolicy() {
        Policy policy = new Policy();

        Boolean startupEnabled = parseBooleanProperty("metadata.extraction.enabled");
        if (startupEnabled != null) {
            policy.setExtractionEnabled(startupEnabled);
            policy.setExtractionEnabledSource("CONFIG_FILE");
        } else {
            policy.setExtractionEnabled(DEFAULT_EXTRACTION_ENABLED);
            policy.setExtractionEnabledSource("BACKEND_DEFAULT");
        }

        Long startupInterval = parseLongProperty("metadata.extraction.scan-interval-ms");
        if (startupInterval != null) {
            policy.setExtractionScanIntervalMs(sanitizeScanInterval(startupInterval));
            policy.setExtractionScanIntervalMsSource("CONFIG_FILE");
        } else {
            policy.setExtractionScanIntervalMs(DEFAULT_SCAN_INTERVAL_MS);
            policy.setExtractionScanIntervalMsSource("BACKEND_DEFAULT");
        }

        return policy;
    }

    private void fillDescriptions(Policy policy) {
        policy.setExtractionEnabledDesc("元数据抽取总开关");
        policy.setExtractionScanIntervalMsDesc("定时抽取间隔（毫秒）");
    }

    private Boolean parseBooleanProperty(String key) {
        if (!environment.containsProperty(key)) {
            return null;
        }
        String value = environment.getProperty(key);
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "false".equals(normalized)) {
            return Boolean.parseBoolean(normalized);
        }
        return null;
    }

    private Long parseLongProperty(String key) {
        if (!environment.containsProperty(key)) {
            return null;
        }
        String value = environment.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long sanitizeScanInterval(Long value) {
        long v = value == null ? DEFAULT_SCAN_INTERVAL_MS : value;
        return Math.max(1000L, v);
    }

    private Long getValueAsLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long) return (Long)obj;
        if (obj instanceof Integer) return ((Integer)obj).longValue();
        if (obj instanceof byte[]) {
             try {
                return Long.parseLong(new String((byte[])obj));
             } catch (NumberFormatException e) {
                return null;
             }
        }
        return null;
    }

    private Boolean getValueAsBoolean(Object obj) {
       if (obj == null) return null;
       if (obj instanceof Boolean) return (Boolean)obj;
       if (obj instanceof byte[]) {
           String raw = new String((byte[])obj).trim().toLowerCase();
           if ("true".equals(raw) || "false".equals(raw)) {
               return Boolean.parseBoolean(raw);
           }
       }
       return null;
    }
}
