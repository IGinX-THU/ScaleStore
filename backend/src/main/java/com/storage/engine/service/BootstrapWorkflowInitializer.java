package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import com.storage.engine.dao.IGinxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BootstrapWorkflowInitializer {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapWorkflowInitializer.class);
    private static final String INIT_WORKFLOW_RELATIVE_PATH = "init/init-data-workflow.yaml";

    @Value("${resource.base-path:classpath:/}")
    private String resourceBasePath;

    @Autowired
    private IGinxDao iginxDao;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String workflowLocation = resolveWorkflowLocation();

        try {
            if (isBootstrapDone()) {
                logger.info("Bootstrap workflow already executed, skip initialization.");
                return;
            }

            List<String> sqlStatements = loadSqlStatements(workflowLocation);
            if (sqlStatements.isEmpty()) {
                logger.warn("Bootstrap workflow file {} has no SQL statements.", workflowLocation);
                return;
            }

            for (String sql : sqlStatements) {
                logger.info("Executing bootstrap SQL: {}", sql);
                iginxDao.executeSql(sql);
            }

            markBootstrapDone(workflowLocation);
            logger.info("Bootstrap workflow completed successfully.");
        } catch (Exception e) {
            logger.error("Bootstrap workflow failed: {}", e.getMessage(), e);
        }
    }

    private boolean isBootstrapDone() {
        try {
            SessionExecuteSqlResult result = iginxDao.executeSql(
                    "select * from " + IGinxConstants.BOOTSTRAP_PATH + " where key = 1;");
            if (result == null || result.getValues() == null || result.getValues().isEmpty() || result.getPaths() == null) {
                return false;
            }

            int initializedIdx = -1;
            List<String> paths = result.getPaths();
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("initialized")) {
                    initializedIdx = i;
                    break;
                }
            }

            if (initializedIdx == -1) {
                return false;
            }

            List<List<Object>> values = result.getValues();
            List<Object> lastRow = values.get(values.size() - 1);
            if (lastRow == null || initializedIdx >= lastRow.size()) {
                return false;
            }

            Boolean initialized = getValueAsBoolean(lastRow.get(initializedIdx));
            return initialized != null && initialized;
        } catch (Exception e) {
            return false;
        }
    }

    private void markBootstrapDone(String workflowLocation) {
        String initTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sql = String.format(
                Locale.ROOT,
                "insert into %s(key, initialized, workflowFile, initTime) values (1, true, '%s', '%s');",
                IGinxConstants.BOOTSTRAP_PATH,
                escapeSql(workflowLocation),
                initTime);
        iginxDao.executeSql(sql);
    }

    private List<String> loadSqlStatements(String workflowLocation) throws Exception {
        List<String> sqlStatements = new ArrayList<String>();

        InputStream in = null;
        try {
            if (isClasspathRoot()) {
                ClassPathResource resource = new ClassPathResource(workflowLocation);
                if (!resource.exists()) {
                    logger.warn("Bootstrap workflow file not found: {}", workflowLocation);
                    return sqlStatements;
                }
                in = resource.getInputStream();
            } else {
                File workflowFile = new File(workflowLocation);
                if (!workflowFile.exists()) {
                    logger.warn("Bootstrap workflow file not found: {}", workflowLocation);
                    return sqlStatements;
                }
                in = new FileInputStream(workflowFile);
            }

            Yaml yaml = new Yaml();
            Object root = yaml.load(in);
            if (!(root instanceof Map)) {
                return sqlStatements;
            }

            Object taskListObj = ((Map<?, ?>) root).get("taskList");
            if (!(taskListObj instanceof List)) {
                return sqlStatements;
            }

            List<?> taskList = (List<?>) taskListObj;
            for (Object taskObj : taskList) {
                if (!(taskObj instanceof Map)) {
                    continue;
                }

                Map<?, ?> task = (Map<?, ?>) taskObj;
                String taskType = asString(task.get("taskType"));
                if (!("sql".equalsIgnoreCase(taskType) || "iginx".equalsIgnoreCase(taskType))) {
                    continue;
                }

                Object sqlListObj = task.containsKey("sqlList") ? task.get("sqlList") : task.get("sqllist");
                if (!(sqlListObj instanceof List)) {
                    continue;
                }

                List<?> sqlList = (List<?>) sqlListObj;
                for (Object sqlObj : sqlList) {
                    String sql = asString(sqlObj).trim();
                    if (!sql.isEmpty()) {
                        sqlStatements.add(sql);
                    }
                }
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }

        return sqlStatements;
    }

    private String resolveWorkflowLocation() {
        if (isClasspathRoot()) {
            return joinClasspathPath(INIT_WORKFLOW_RELATIVE_PATH);
        }

        String root = normalizeFileRoot(removeFilePrefix(resourceBasePath));
        return new File(root, INIT_WORKFLOW_RELATIVE_PATH.replace("/", File.separator)).getPath();
    }

    private boolean isClasspathRoot() {
        String root = resourceBasePath == null ? "" : resourceBasePath.trim();
        return root.isEmpty() || ".".equals(root) || root.startsWith("classpath:");
    }

    private String joinClasspathPath(String relativePath) {
        String root = resourceBasePath == null ? "" : resourceBasePath.trim();
        if (root.startsWith("classpath:")) {
            root = root.substring("classpath:".length());
        }
        root = normalizeRoot(root);
        if (root.isEmpty() || ".".equals(root)) {
            return relativePath;
        }
        return root + "/" + relativePath;
    }

    private String normalizeRoot(String root) {
        if (root == null) {
            return "";
        }
        String normalized = root.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String removeFilePrefix(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("file:")) {
            return trimmed.substring("file:".length());
        }
        return trimmed;
    }

    private String normalizeFileRoot(String root) {
        if (root == null) {
            return "";
        }
        String normalized = root.trim();
        while ((normalized.endsWith("/") || normalized.endsWith("\\")) && normalized.length() > 1) {
            if (normalized.length() == 3 && normalized.charAt(1) == ':') {
                break;
            }
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String asString(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                return "";
            }

            // Some YAML plain scalars containing ": " can be parsed as a single-entry map.
            // Rebuild the original scalar text instead of using Map#toString() (which adds {...}).
            if (map.size() == 1) {
                Map.Entry<?, ?> entry = map.entrySet().iterator().next();
                String key = asString(entry.getKey()).trim();
                String val = asString(entry.getValue()).trim();
                if (val.isEmpty()) {
                    return key;
                }
                return key + ": " + val;
            }

            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(asString(entry.getKey()).trim());
                sb.append(": ");
                sb.append(asString(entry.getValue()).trim());
                first = false;
            }
            return sb.toString();
        }

        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(asString(list.get(i)).trim());
            }
            return sb.toString();
        }

        return String.valueOf(value);
    }

    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
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
