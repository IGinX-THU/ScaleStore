package com.storage.engine.service;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataFlowType;
import cn.edu.tsinghua.iginx.thrift.ExportType;
import cn.edu.tsinghua.iginx.thrift.JobState;
import cn.edu.tsinghua.iginx.thrift.TaskInfo;
import cn.edu.tsinghua.iginx.thrift.TaskType;
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
    private static final String TRANSFORM_SQL_TAIL_QUERY = "select key from sys.user where key = 1;";

    @Value("${resource.base-path:classpath:/}")
    private String resourceBasePath;

    @Value("${iginx.host}")
    private String iginxHost;

    @Value("${iginx.port}")
    private int iginxPort;

    @Value("${iginx.username}")
    private String iginxUsername;

    @Value("${iginx.password}")
    private String iginxPassword;

    @Value("${bootstrap.workflow.timeout-ms:600000}")
    private long bootstrapWorkflowTimeoutMs;

    @Value("${bootstrap.workflow.poll-interval-ms:500}")
    private long bootstrapWorkflowPollIntervalMs;

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

            WorkflowSpec workflowSpec = loadWorkflowSpec(workflowLocation);
            if (workflowSpec == null || workflowSpec.taskInfoList.isEmpty()) {
                logger.warn("Bootstrap workflow file {} has no valid task definitions.", workflowLocation);
                return;
            }

            JobExecution execution = commitAndWaitBootstrapJob(workflowSpec);
            if (!isBootstrapSuccess(execution.finalState)) {
                throw new IllegalStateException("Bootstrap transform job finished with state " + execution.finalState);
            }

            markBootstrapDone(workflowLocation, execution.jobId, execution.finalState);
            logger.info("Bootstrap workflow completed successfully via transform job: jobId={}, state={}",
                    execution.jobId, execution.finalState);
        } catch (Exception e) {
            logger.error("Bootstrap workflow failed: {}", e.getMessage(), e);
        }
    }

    private JobExecution commitAndWaitBootstrapJob(WorkflowSpec workflowSpec) throws SessionException {
        Session session = null;
        try {
            session = new Session(iginxHost, iginxPort, iginxUsername, iginxPassword);
            session.openSession();

            long jobId = commitTransformJob(session, workflowSpec);
            logger.info("Bootstrap workflow submitted as transform job: jobId={}", jobId);

            JobState finalState = waitForTerminalState(session, jobId);
            return new JobExecution(jobId, finalState);
        } finally {
            if (session != null) {
                try {
                    session.closeSession();
                } catch (Exception ignore) {
                    // ignore close exception
                }
            }
        }
    }

    private long commitTransformJob(Session session, WorkflowSpec workflowSpec) throws SessionException {
        String exportFile = safe(workflowSpec.exportFile);
        if (workflowSpec.exportType == ExportType.FILE && exportFile.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap workflow exportType=FILE requires non-empty exportFile");
        }

        String schedule = safe(workflowSpec.schedule);
        if (schedule.isEmpty()) {
            return session.commitTransformJob(workflowSpec.taskInfoList, workflowSpec.exportType, exportFile);
        }

        return session.commitTransformJob(
                workflowSpec.taskInfoList,
                workflowSpec.exportType,
                exportFile,
                schedule,
                workflowSpec.stopOnFailure);
    }

    private JobState waitForTerminalState(Session session, long jobId) throws SessionException {
        long timeoutMs = Math.max(1000L, bootstrapWorkflowTimeoutMs);
        long pollInterval = Math.max(100L, bootstrapWorkflowPollIntervalMs);
        long deadline = System.currentTimeMillis() + timeoutMs;

        JobState latest = JobState.JOB_UNKNOWN;
        while (System.currentTimeMillis() <= deadline) {
            latest = session.queryTransformJobStatus(jobId);
            if (isTerminal(latest)) {
                return latest;
            }

            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return latest;
            }
        }

        return latest;
    }

    private boolean isTerminal(JobState state) {
        if (state == null) {
            return false;
        }

        return state == JobState.JOB_FINISHED
                || state == JobState.JOB_CLOSED
                || state == JobState.JOB_FAILED
                || state == JobState.JOB_FAILING
                || state == JobState.JOB_PARTIALLY_FAILED
                || state == JobState.JOB_PARTIALLY_FAILING
                || state == JobState.JOB_UNKNOWN;
    }

    private boolean isBootstrapSuccess(JobState state) {
        return state == JobState.JOB_FINISHED || state == JobState.JOB_CLOSED;
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

    private void markBootstrapDone(String workflowLocation, long jobId, JobState finalState) {
        String initTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sql = String.format(
                Locale.ROOT,
                "insert into %s(key, initialized, workflowFile, initTime, initJobId, initJobState) values (1, true, '%s', '%s', %d, '%s');",
                IGinxConstants.BOOTSTRAP_PATH,
                escapeSql(workflowLocation),
                initTime,
                jobId,
                escapeSql(finalState == null ? "" : finalState.name()));
        iginxDao.executeSql(sql);
    }

    private WorkflowSpec loadWorkflowSpec(String workflowLocation) throws Exception {
        WorkflowSpec spec = new WorkflowSpec();
        spec.exportType = ExportType.LOG;
        spec.exportFile = "";
        spec.schedule = "";

        InputStream in = null;
        try {
            if (isClasspathRoot()) {
                ClassPathResource resource = new ClassPathResource(workflowLocation);
                if (!resource.exists()) {
                    logger.warn("Bootstrap workflow file not found: {}", workflowLocation);
                    return null;
                }
                in = resource.getInputStream();
            } else {
                File workflowFile = new File(workflowLocation);
                if (!workflowFile.exists()) {
                    logger.warn("Bootstrap workflow file not found: {}", workflowLocation);
                    return null;
                }
                in = new FileInputStream(workflowFile);
            }

            Yaml yaml = new Yaml();
            Object root = yaml.load(in);
            if (!(root instanceof Map)) {
                return null;
            }

            Map<?, ?> map = (Map<?, ?>) root;

            Object taskListObj = map.get("taskList");
            if (!(taskListObj instanceof List)) {
                return null;
            }

            spec.taskInfoList = parseTaskInfoList((List<?>) taskListObj);
            spec.exportType = parseExportType(asString(map.get("exportType")));
            spec.exportFile = safe(asString(map.get("exportFile")));
            spec.schedule = safe(asString(map.get("schedule")));
            spec.stopOnFailure = parseBoolean(map.get("stopOnFailure"), true);
        } finally {
            if (in != null) {
                in.close();
            }
        }

        return spec;
    }

    private List<TaskInfo> parseTaskInfoList(List<?> taskList) {
        List<TaskInfo> out = new ArrayList<TaskInfo>();
        for (Object taskObj : taskList) {
            if (!(taskObj instanceof Map)) {
                continue;
            }

            Map<?, ?> task = (Map<?, ?>) taskObj;
            String taskTypeRaw = safe(asString(task.get("taskType"))).toLowerCase(Locale.ROOT);
            TaskType taskType;
            if ("sql".equals(taskTypeRaw) || "iginx".equals(taskTypeRaw)) {
                taskType = TaskType.SQL;
            } else if ("python".equals(taskTypeRaw)) {
                taskType = TaskType.PYTHON;
            } else {
                continue;
            }

            String flowRaw = safe(asString(task.get("dataFlowType"))).toLowerCase(Locale.ROOT);
            DataFlowType flowType = "batch".equals(flowRaw) ? DataFlowType.BATCH : DataFlowType.STREAM;
            TaskInfo info = new TaskInfo(taskType, flowType);

            Long timeout = parseLong(task.get("timeout"));
            if (timeout != null && timeout > 0L) {
                info.setTimeout(timeout);
            }

            if (taskType == TaskType.SQL) {
                Object sqlListObj = task.containsKey("sqlList") ? task.get("sqlList") : task.get("sqllist");
                if (!(sqlListObj instanceof List)) {
                    continue;
                }

                List<String> sqlList = new ArrayList<String>();
                for (Object sqlObj : (List<?>) sqlListObj) {
                    String sql = safe(asString(sqlObj));
                    if (!sql.isEmpty()) {
                        sqlList.add(sql);
                    }
                }
                if (sqlList.isEmpty()) {
                    continue;
                }

                ensureTransformSqlTail(sqlList);
                info.setSqlList(sqlList);
            } else {
                String pyTaskName = safe(asString(task.get("pyTaskName")));
                if (pyTaskName.isEmpty()) {
                    continue;
                }
                info.setPyTaskName(pyTaskName);
            }

            out.add(info);
        }

        return out;
    }

    private void ensureTransformSqlTail(List<String> sqlList) {
        if (sqlList == null || sqlList.isEmpty()) {
            return;
        }

        String tail = safe(sqlList.get(sqlList.size() - 1)).toLowerCase(Locale.ROOT);
        if (tail.startsWith("select") || tail.startsWith("show")) {
            return;
        }

        // Transform SQL stage requires the last statement to be query-like (select/show).
        sqlList.add(TRANSFORM_SQL_TAIL_QUERY);
    }

    private ExportType parseExportType(String exportTypeRaw) {
        String value = safe(exportTypeRaw).toLowerCase(Locale.ROOT);
        if ("file".equals(value)) {
            return ExportType.FILE;
        }
        if ("iginx".equals(value)) {
            return ExportType.IGINX;
        }
        return ExportType.LOG;
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

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String raw = safe(String.valueOf(value));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String raw = safe(asString(value)).toLowerCase(Locale.ROOT);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return defaultValue;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
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

    private static class WorkflowSpec {
        private List<TaskInfo> taskInfoList;
        private ExportType exportType;
        private String exportFile;
        private String schedule;
        private boolean stopOnFailure;
    }

    private static class JobExecution {
        private final long jobId;
        private final JobState finalState;

        private JobExecution(long jobId, JobState finalState) {
            this.jobId = jobId;
            this.finalState = finalState;
        }
    }
}
