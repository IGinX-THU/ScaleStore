package com.storage.engine.component;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.thrift.DataFlowType;
import cn.edu.tsinghua.iginx.thrift.ExportType;
import cn.edu.tsinghua.iginx.thrift.IginxInfo;
import cn.edu.tsinghua.iginx.thrift.TaskInfo;
import cn.edu.tsinghua.iginx.thrift.TaskType;
import com.storage.engine.dao.IGinxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class MetadataTransformJobInitializer {

    private static final Logger logger = LoggerFactory.getLogger(MetadataTransformJobInitializer.class);

    private static final String LEAF_WORKFLOW_RELATIVE_PATH = "init/metadata-semantic-leaf-workflow.yaml";
    private static final String DIRECTORY_WORKFLOW_RELATIVE_PATH = "init/metadata-semantic-directory-workflow.yaml";
    private static final String TRANSFORM_SQL_TAIL_QUERY = "select key from sys.user where key = 1;";

    @Value("${resource.base-path:classpath:/}")
    private String resourceBasePath;

    @Value("${iginx.host}")
    private String seedHost;

    @Value("${iginx.port}")
    private int seedPort;

    @Value("${iginx.username}")
    private String iginxUsername;

    @Value("${iginx.password}")
    private String iginxPassword;

    @Value("${metadata.extraction.enabled:true}")
    private boolean startupExtractionEnabled;

    @Value("${metadata.extraction.scan-interval-ms:60000}")
    private long startupScanIntervalMs;

    @Autowired
    private IGinxDao iginxDao;

    private final Map<String, RegisteredJob> registeredJobs = new LinkedHashMap<String, RegisteredJob>();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!startupExtractionEnabled) {
            logger.info("Metadata transform workflow startup registration skipped because extraction.enabled=false.");
            return;
        }

        try {
            registerScheduledJobsForAllNodes(sanitizeScanInterval(startupScanIntervalMs), "startup");
        } catch (Exception e) {
            logger.error("Metadata transform workflow startup registration failed: {}", e.getMessage(), e);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        try {
            cancelAllRegisteredJobs("application shutdown");
        } catch (Exception e) {
            logger.warn("Failed to cancel metadata transform jobs on shutdown: {}", e.getMessage());
        }
    }

    public synchronized void onPolicyUpdated(
            boolean oldEnabled,
            long oldIntervalMs,
            boolean newEnabled,
            long newIntervalMs) {
        long safeOldIntervalMs = sanitizeScanInterval(oldIntervalMs);
        long safeNewIntervalMs = sanitizeScanInterval(newIntervalMs);

        try {
            if (!newEnabled) {
                cancelAllRegisteredJobs("policy extraction.enabled=false");
                return;
            }

            if (!oldEnabled) {
                cancelAllRegisteredJobs("policy extraction.enabled changed false->true");
                registerScheduledJobsForAllNodes(safeNewIntervalMs, "policy extraction.enabled=true");
                return;
            }

            if (safeOldIntervalMs != safeNewIntervalMs) {
                cancelAllRegisteredJobs("policy extraction.scan-interval-ms changed");
                registerScheduledJobsForAllNodes(safeNewIntervalMs, "policy interval changed");
                return;
            }

            if (registeredJobs.isEmpty()) {
                registerScheduledJobsForAllNodes(safeNewIntervalMs, "policy unchanged but no active tracked job");
            }
        } catch (Exception e) {
            logger.error("Failed to apply transform policy change: {}", e.getMessage(), e);
        }
    }

    private synchronized void registerScheduledJobsForAllNodes(long scanIntervalMs, String reason) throws Exception {
        List<WorkflowRegistration> workflowRegistrations = buildWorkflowRegistrations();
        if (workflowRegistrations.isEmpty()) {
            logger.warn("Metadata transform workflow registration skipped because no workflow definitions were available.");
            return;
        }

        List<Endpoint> endpoints = listClusterEndpoints();
        if (endpoints.isEmpty()) {
            endpoints.add(new Endpoint(seedHost, seedPort));
        }

        Map<String, RegisteredJob> nextJobs = new LinkedHashMap<String, RegisteredJob>();
        int successCount = 0;
        int totalCount = endpoints.size() * workflowRegistrations.size();
        for (Endpoint endpoint : endpoints) {
            for (WorkflowRegistration registration : workflowRegistrations) {
                try {
                    WorkflowSpec workflowSpec = copyWorkflowSpec(registration.spec);
                    workflowSpec.schedule = buildScheduleFromIntervalMs(scanIntervalMs);
                    long jobId = registerWorkflow(endpoint, workflowSpec);
                    successCount++;
                    nextJobs.put(endpoint.key() + "#" + registration.name,
                            new RegisteredJob(registration.name, endpoint, jobId, workflowSpec.schedule));
                    logger.info("Metadata transform workflow submitted: workflow={}, endpoint={}, jobId={}, schedule={}, reason={}",
                            registration.name, endpoint, jobId, safe(workflowSpec.schedule), safe(reason));
                } catch (Exception e) {
                    logger.error("Metadata transform workflow registration failed: workflow={}, endpoint={}, error={}",
                            registration.name, endpoint, e.getMessage(), e);
                }
            }
        }

        if (successCount == 0) {
            throw new IllegalStateException("Metadata transform workflow registration failed on all IGinX endpoints");
        }

        registeredJobs.clear();
        registeredJobs.putAll(nextJobs);

        logger.info("Metadata transform workflow registration completed: success={}/{}, reason={}, schedule={}",
                successCount, totalCount, safe(reason), buildScheduleFromIntervalMs(scanIntervalMs));
    }

    private synchronized int cancelAllRegisteredJobs(String reason) {
        if (registeredJobs.isEmpty()) {
            logger.info("No tracked metadata transform jobs to cancel, reason={}", safe(reason));
            return 0;
        }

        List<RegisteredJob> jobs = new ArrayList<RegisteredJob>(registeredJobs.values());
        registeredJobs.clear();

        int canceledCount = 0;
        for (RegisteredJob job : jobs) {
            if (cancelTransformJob(job.endpoint, job.jobId)) {
                canceledCount++;
            }
        }

        logger.info("Metadata transform jobs cancel finished: canceled={}/{}, reason={}",
                canceledCount, jobs.size(), safe(reason));
        return canceledCount;
    }

    private boolean cancelTransformJob(Endpoint endpoint, long jobId) {
        Session session = null;
        try {
            session = new Session(endpoint.host, endpoint.port, iginxUsername, iginxPassword);
            session.openSession();
            session.cancelTransformJob(jobId);
            logger.info("Canceled metadata transform job: endpoint={}, jobId={}", endpoint, jobId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to cancel metadata transform job: endpoint={}, jobId={}, error={}",
                    endpoint, jobId, e.getMessage());
            return false;
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

    private String buildScheduleFromIntervalMs(long intervalMs) {
        long safeIntervalMs = sanitizeScanInterval(intervalMs);
        long seconds = (safeIntervalMs + 999L) / 1000L;
        return "every " + seconds + " second";
    }

    private long sanitizeScanInterval(long intervalMs) {
        return Math.max(1000L, intervalMs);
    }

    private long registerWorkflow(Endpoint endpoint, WorkflowSpec workflowSpec) throws SessionException {
        Session session = null;
        try {
            session = new Session(endpoint.host, endpoint.port, iginxUsername, iginxPassword);
            session.openSession();
            return commitTransformJob(session, workflowSpec);
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
            throw new IllegalArgumentException("Metadata workflow exportType=FILE requires non-empty exportFile");
        }

        String schedule = safe(workflowSpec.schedule);
        if (schedule.isEmpty()) {
            return session.commitTransformJob(workflowSpec.taskInfoList, workflowSpec.exportType, exportFile);
        }

        return commitTransformJobWithSchedule(session, workflowSpec, exportFile, schedule);
    }

    private long commitTransformJobWithSchedule(
            Session session,
            WorkflowSpec workflowSpec,
            String exportFile,
            String schedule) throws SessionException {
        try {
            Method withStopOnFailure = session.getClass().getMethod(
                    "commitTransformJob",
                    List.class,
                    ExportType.class,
                    String.class,
                    String.class,
                    Boolean.TYPE);
            Object ret = withStopOnFailure.invoke(
                    session,
                    workflowSpec.taskInfoList,
                    workflowSpec.exportType,
                    exportFile,
                    schedule,
                    Boolean.valueOf(workflowSpec.stopOnFailure));
            return toJobId(ret);
        } catch (NoSuchMethodException e) {
            try {
                Method withoutStopOnFailure = session.getClass().getMethod(
                        "commitTransformJob",
                        List.class,
                        ExportType.class,
                        String.class,
                        String.class);
                Object ret = withoutStopOnFailure.invoke(
                        session,
                        workflowSpec.taskInfoList,
                        workflowSpec.exportType,
                        exportFile,
                        schedule);
                return toJobId(ret);
            } catch (ReflectiveOperationException inner) {
                Throwable cause = inner.getCause();
                if (cause instanceof SessionException) {
                    throw (SessionException) cause;
                }
                throw new RuntimeException("Failed to invoke scheduled commitTransformJob by reflection", inner);
            }
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SessionException) {
                throw (SessionException) cause;
            }
            throw new RuntimeException("Failed to invoke scheduled commitTransformJob by reflection", e);
        }
    }

    private long toJobId(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalStateException("Unexpected job id type: " + (value == null ? "null" : value.getClass()));
    }

    private List<Endpoint> listClusterEndpoints() {
        Set<String> dedup = new LinkedHashSet<String>();
        List<Endpoint> endpoints = new ArrayList<Endpoint>();

        try {
            ClusterInfo clusterInfo = iginxDao.getClusterInfo();
            if (clusterInfo != null && clusterInfo.getIginxInfos() != null) {
                for (IginxInfo info : clusterInfo.getIginxInfos()) {
                    if (info == null) {
                        continue;
                    }
                    String host = safe(info.getIp());
                    int port = info.getPort();
                    if (host.isEmpty() || port <= 0) {
                        continue;
                    }
                    String key = host + ":" + port;
                    if (dedup.add(key)) {
                        endpoints.add(new Endpoint(host, port));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to list IGinX cluster nodes for transform registration: {}", e.getMessage());
        }

        return endpoints;
    }

    private WorkflowSpec loadWorkflowSpec(String workflowLocation) throws Exception {
        WorkflowSpec spec = new WorkflowSpec();
        spec.exportType = ExportType.LOG;
        spec.exportFile = "";
        spec.schedule = "";
        spec.stopOnFailure = true;

        InputStream in = null;
        try {
            if (isClasspathRoot()) {
                ClassPathResource resource = new ClassPathResource(workflowLocation);
                if (!resource.exists()) {
                    logger.warn("Metadata transform workflow file not found: {}", workflowLocation);
                    return null;
                }
                in = resource.getInputStream();
            } else {
                File workflowFile = new File(workflowLocation);
                if (!workflowFile.exists()) {
                    logger.warn("Metadata transform workflow file not found: {}", workflowLocation);
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

    private List<WorkflowRegistration> buildWorkflowRegistrations() throws Exception {
        List<WorkflowRegistration> registrations = new ArrayList<WorkflowRegistration>();
        appendWorkflowRegistration(registrations, "leaf", resolveWorkflowLocation(LEAF_WORKFLOW_RELATIVE_PATH));
        appendWorkflowRegistration(registrations, "directory", resolveWorkflowLocation(DIRECTORY_WORKFLOW_RELATIVE_PATH));
        return registrations;
    }

    private void appendWorkflowRegistration(
            List<WorkflowRegistration> registrations,
            String name,
            String workflowLocation) throws Exception {
        WorkflowSpec workflowSpec = loadWorkflowSpec(workflowLocation);
        if (workflowSpec == null || workflowSpec.taskInfoList == null || workflowSpec.taskInfoList.isEmpty()) {
            logger.warn("Metadata transform workflow file {} has no valid task definitions.", workflowLocation);
            return;
        }
        registrations.add(new WorkflowRegistration(name, workflowLocation, workflowSpec));
    }

    private WorkflowSpec copyWorkflowSpec(WorkflowSpec source) {
        WorkflowSpec target = new WorkflowSpec();
        target.taskInfoList = new ArrayList<TaskInfo>(source.taskInfoList);
        target.exportType = source.exportType;
        target.exportFile = source.exportFile;
        target.schedule = source.schedule;
        target.stopOnFailure = source.stopOnFailure;
        return target;
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

                String outputPrefix = safe(asString(task.get("outputPrefix")));
                if (!outputPrefix.isEmpty()) {
                    trySetOutputPrefix(info, outputPrefix);
                }
            }

            out.add(info);
        }
        return out;
    }

    private void trySetOutputPrefix(TaskInfo info, String outputPrefix) {
        try {
            Method method = info.getClass().getMethod("setOutputPrefix", String.class);
            method.invoke(info, outputPrefix);
        } catch (NoSuchMethodException e) {
            logger.warn("TaskInfo#setOutputPrefix is unavailable, outputPrefix [{}] may be ignored.", outputPrefix);
        } catch (Exception e) {
            logger.warn("Failed to set outputPrefix [{}]: {}", outputPrefix, e.getMessage());
        }
    }

    private void ensureTransformSqlTail(List<String> sqlList) {
        if (sqlList == null || sqlList.isEmpty()) {
            return;
        }

        String tail = safe(sqlList.get(sqlList.size() - 1)).toLowerCase(Locale.ROOT);
        if (tail.startsWith("select") || tail.startsWith("show")) {
            return;
        }

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

    private String resolveWorkflowLocation(String workflowRelativePath) {
        if (isClasspathRoot()) {
            return joinClasspathPath(workflowRelativePath);
        }

        String root = normalizeFileRoot(removeFilePrefix(resourceBasePath));
        return new File(root, workflowRelativePath.replace("/", File.separator)).getPath();
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

    private static class WorkflowSpec {
        private List<TaskInfo> taskInfoList;
        private ExportType exportType;
        private String exportFile;
        private String schedule;
        private boolean stopOnFailure;
    }

    private static class WorkflowRegistration {
        private final String name;
        private final String workflowLocation;
        private final WorkflowSpec spec;

        private WorkflowRegistration(String name, String workflowLocation, WorkflowSpec spec) {
            this.name = name;
            this.workflowLocation = workflowLocation;
            this.spec = spec;
        }
    }

    private static class Endpoint {
        private final String host;
        private final int port;

        private Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private String key() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private static class RegisteredJob {
        private final String workflowName;
        private final Endpoint endpoint;
        private final long jobId;
        private final String schedule;

        private RegisteredJob(String workflowName, Endpoint endpoint, long jobId, String schedule) {
            this.workflowName = workflowName;
            this.endpoint = endpoint;
            this.jobId = jobId;
            this.schedule = schedule;
        }
    }
}
