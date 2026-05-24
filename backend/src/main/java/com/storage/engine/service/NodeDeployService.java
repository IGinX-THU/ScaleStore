package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.thrift.IginxInfo;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import com.storage.engine.utils.ScriptExecutionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class NodeDeployService {

    private static final int DEPLOY_TIMEOUT_SECONDS = 180;
    private static final int CLUSTER_WAIT_SECONDS = 30;
    private static final int MAX_LOG_LINES = 400;
    private static final String DEPLOY_SCRIPT_RELATIVE_PATH = "scripts/deploy_iginx.sh";
    private static final String STOP_SCRIPT_RELATIVE_PATH = "scripts/stop_iginx.sh";

    private final Map<String, DeployTaskState> taskStates = new ConcurrentHashMap<String, DeployTaskState>();
    private final ExecutorService deployExecutor = Executors.newCachedThreadPool();

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private ScriptExecutionUtils scriptExecutionUtils;

    @Value("${deploy.iginx.default-package-path:/home/ubuntu/IGinX-0.9.0-SNAPSHOT.tar.gz}")
    private String defaultPackagePath;

    @Value("${deploy.iginx.default-zk-connection:127.0.0.1:2181}")
    private String defaultZkConnection;

    @Value("${deploy.iginx.default-directory:/opt/iginx}")
    private String defaultDeployDirectory;

    @Value("${deploy.iginx.default-python-cmd:python3}")
    private String defaultPythonCmd;

    // ==================== Deploy (Add Node) ====================

    public NodeDeployTaskStatus startDeployTask(final NodeDeployRequest request, final int nodeId,
                                                final String nodePort, final Runnable successAction) {
        String targetIp = required(request.getIp(), "节点IP不能为空");
        String username = required(request.getSshUsername(), "SSH用户名不能为空");
        String password = required(request.getSshPassword(), "SSH密码不能为空");
        String sshPort = resolveSshPort(request.getSshPort());

        String packagePath = safeValue(request.getPackagePath(), defaultPackagePath);
        File packageFile = new File(packagePath);
        if (!packageFile.exists()) {
            throw new RuntimeException("部署包不存在: " + packagePath);
        }

        String deployDir = safeValue(request.getDeployDirectory(), defaultDeployDirectory);
        String zkConnection = safeValue(request.getZookeeperConnectionString(), defaultZkConnection);
        String pythonCmd = safeValue(request.getPythonCmd(), defaultPythonCmd);

        File scriptFile = scriptExecutionUtils.resolveScriptFile(DEPLOY_SCRIPT_RELATIVE_PATH, "部署脚本");
        File udfListFile = scriptExecutionUtils.resolveResourceFile("udf/udf_list", "UDF列表文件");
        File metadataDir = resolveMetadataDirectory();

        String iginxPort = safeValue(request.getPort(), "6888");

        final List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add(scriptFile.getAbsolutePath());
        command.add(targetIp);
        command.add(username);
        command.add(password);
        command.add(sshPort);
        command.add(packageFile.getAbsolutePath());
        command.add(deployDir);
        command.add(zkConnection);
        command.add(iginxPort);
        command.add(pythonCmd);
        command.add(udfListFile.getAbsolutePath());
        command.add(metadataDir.getAbsolutePath());

        final String taskId = UUID.randomUUID().toString();
        final DeployTaskState taskState = new DeployTaskState(taskId);
        taskStates.put(taskId, taskState);

        deployExecutor.submit(new Runnable() {
            @Override
            public void run() {
                taskState.currentStep = "正在执行部署脚本";
                appendLog(taskState, "[STEP] 开始执行部署脚本");
                try {
                    List<ClusterNodeInfo> beforeClusterInfos = safeGetClusterNodeInfos();
                    runCommand(command, taskState);
                    taskState.currentStep = "正在校验 show cluster info";
                    appendLog(taskState, "[STEP] 开始校验节点是否加入集群");
                    ClusterNodeInfo joinedNode = waitForNodeJoinCluster(
                            request.getIp(), nodePort, taskState, beforeClusterInfos);
                    if (joinedNode != null && joinedNode.getClusterId() != null) {
                        request.setId(joinedNode.getClusterId());
                    }
                    if (joinedNode != null
                            && joinedNode.getIp() != null
                            && !joinedNode.getIp().equals(request.getIp())) {
                        appendLog(taskState,
                                "[INFO] show cluster info 返回IP为 " + joinedNode.getIp()
                                        + "（与输入IP " + request.getIp() + " 不同，已按 clusterId 关联）");
                    }
                    successAction.run();
                    taskState.status = "SUCCESS";
                    taskState.completed = true;
                    taskState.currentStep = "部署完成";
                    appendLog(taskState, "[DONE] 节点部署并入集群成功");
                } catch (Exception e) {
                    taskState.status = "FAILED";
                    taskState.completed = true;
                    taskState.errorMessage = e.getMessage();
                    taskState.currentStep = "部署失败";
                    appendLog(taskState, "[ERROR] " + e.getMessage());
                }
            }
        });

        return toTaskStatus(taskState);
    }

    // ==================== Stop (Delete Node) ====================

    public NodeDeployTaskStatus startStopTask(final String targetIp, final String nodePort, final String username,
                                              final String sshPort, final String password, final String deployDirectory,
                                              final Integer expectedClusterId, final Runnable successAction) {
        required(targetIp, "节点IP不能为空");
        required(username, "SSH用户名不能为空");
        required(password, "SSH密码不能为空");
        String resolvedSshPort = resolveSshPort(sshPort);

        String deployDir = safeValue(deployDirectory, defaultDeployDirectory);

        File scriptFile = scriptExecutionUtils.resolveScriptFile(STOP_SCRIPT_RELATIVE_PATH, "停止脚本");

        final List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add(scriptFile.getAbsolutePath());
        command.add(targetIp);
        command.add(username);
        command.add(password);
        command.add(resolvedSshPort);
        command.add(deployDir);
        command.add(safeValue(nodePort, "6888"));

        final String taskId = UUID.randomUUID().toString();
        final DeployTaskState taskState = new DeployTaskState(taskId);
        taskStates.put(taskId, taskState);

        deployExecutor.submit(new Runnable() {
            @Override
            public void run() {
                taskState.currentStep = "正在执行停止脚本";
                appendLog(taskState, "[STEP] 开始执行停止脚本");
                try {
                    List<ClusterNodeInfo> beforeClusterInfos = safeGetClusterNodeInfos();
                    runCommand(command, taskState);
                    taskState.currentStep = "正在校验节点已移除";
                    appendLog(taskState, "[STEP] 正在校验节点是否已从集群中移除");
                    waitForNodeLeaveCluster(targetIp, nodePort, expectedClusterId, taskState, beforeClusterInfos);
                    successAction.run();
                    taskState.status = "SUCCESS";
                    taskState.completed = true;
                    taskState.currentStep = "停止完成";
                    appendLog(taskState, "[DONE] 节点已成功停止并从集群移除");
                } catch (Exception e) {
                    taskState.status = "FAILED";
                    taskState.completed = true;
                    taskState.errorMessage = e.getMessage();
                    taskState.currentStep = "停止失败";
                    appendLog(taskState, "[ERROR] " + e.getMessage());
                }
            }
        });

        return toTaskStatus(taskState);
    }

    // ==================== Task Status ====================

    public NodeDeployTaskStatus getTaskStatus(String taskId) {
        DeployTaskState taskState = taskStates.get(taskId);
        if (taskState == null) {
            return null;
        }
        return toTaskStatus(taskState);
    }

    // ==================== Cluster Info ====================

    public List<ClusterNodeInfo> getClusterNodeInfos() {
        ClusterInfo clusterInfo = iginxDao.getClusterInfo();
        List<ClusterNodeInfo> infos = new ArrayList<ClusterNodeInfo>();
        if (clusterInfo.getIginxInfos() != null) {
            for (IginxInfo iginxInfo : clusterInfo.getIginxInfos()) {
                ClusterNodeInfo info = new ClusterNodeInfo();
                info.setClusterId((int) iginxInfo.getId());
                info.setIp(iginxInfo.getIp());
                info.setPort(String.valueOf(iginxInfo.getPort()));
                info.setNodeType("iginx");
                infos.add(info);
            }
        }
        return infos;
    }

    // ==================== Internal Helpers ====================

    private ClusterNodeInfo waitForNodeJoinCluster(String targetIp, String nodePort,
                                                   DeployTaskState taskState,
                                                   List<ClusterNodeInfo> baselineInfos) {
        String expectedPort = safeValue(nodePort, "6888");
        List<ClusterNodeInfo> beforeInfos = baselineInfos == null
                ? Collections.<ClusterNodeInfo>emptyList()
                : baselineInfos;
        Set<String> baselineKeys = buildClusterKeySet(beforeInfos);
        int baselineCount = beforeInfos.size();

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CLUSTER_WAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            List<ClusterNodeInfo> currentInfos = safeGetClusterNodeInfos();
            ClusterNodeInfo exact = findNodeByIpPort(currentInfos, targetIp, expectedPort);
            if (exact != null) {
                return exact;
            }

            ClusterNodeInfo newByPort = findNewNodeByPort(currentInfos, baselineKeys, expectedPort);
            if (newByPort != null && currentInfos.size() > baselineCount) {
                return newByPort;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待节点加入集群被中断", e);
            }
            appendLog(taskState, "[WAIT] show cluster info 中暂未发现 " + targetIp + ":" + expectedPort);
        }
        throw new RuntimeException("部署脚本执行成功，但在 show cluster info 中未检测到节点: "
                + targetIp + ":" + expectedPort);
    }

    private void waitForNodeLeaveCluster(String targetIp, String nodePort, Integer expectedClusterId,
                                         DeployTaskState taskState,
                                         List<ClusterNodeInfo> baselineInfos) {
        String expectedPort = safeValue(nodePort, "6888");
        List<ClusterNodeInfo> beforeInfos = baselineInfos == null
                ? Collections.<ClusterNodeInfo>emptyList()
                : baselineInfos;
        int baselineCount = beforeInfos.size();
        boolean hadClusterIdAtStart = expectedClusterId != null
                && clusterNodeIdExists(beforeInfos, expectedClusterId);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CLUSTER_WAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            List<ClusterNodeInfo> currentInfos = safeGetClusterNodeInfos();
            if (expectedClusterId != null) {
                if (!clusterNodeIdExists(currentInfos, expectedClusterId)) {
                    return;
                }
            } else if (!nodeExistsInClusterInfo(currentInfos, targetIp, expectedPort)) {
                if (currentInfos.size() < baselineCount || baselineCount == 0) {
                    return;
                }
            } else if (!hadClusterIdAtStart && currentInfos.size() < baselineCount) {
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待节点离开集群被中断", e);
            }
            appendLog(taskState, "[WAIT] show cluster info 中仍存在 " + targetIp + ":" + expectedPort);
        }
        if (expectedClusterId != null) {
            throw new RuntimeException("停止脚本执行成功，但在 show cluster info 中仍检测到节点 clusterId="
                    + expectedClusterId + " (" + targetIp + ":" + expectedPort + ")");
        }
        throw new RuntimeException("停止脚本执行成功，但在 show cluster info 中仍检测到节点: " + targetIp + ":" + expectedPort);
    }

    private boolean nodeExistsInClusterInfo(String targetIp, String targetPort) {
        try {
            ClusterInfo clusterInfo = iginxDao.getClusterInfo();
            if (clusterInfo.getIginxInfos() != null) {
                for (IginxInfo iginxInfo : clusterInfo.getIginxInfos()) {
                    if (targetIp.equals(iginxInfo.getIp())
                            && targetPort.equals(String.valueOf(iginxInfo.getPort()))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nodeExistsInClusterInfo(List<ClusterNodeInfo> infos, String targetIp, String targetPort) {
        for (ClusterNodeInfo info : infos) {
            if (targetIp.equals(info.getIp()) && targetPort.equals(info.getPort())) {
                return true;
            }
        }
        return false;
    }

    private List<ClusterNodeInfo> safeGetClusterNodeInfos() {
        try {
            return getClusterNodeInfos();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Set<String> buildClusterKeySet(List<ClusterNodeInfo> infos) {
        Set<String> keys = new HashSet<String>();
        for (ClusterNodeInfo info : infos) {
            keys.add(clusterKey(info.getIp(), info.getPort()));
        }
        return keys;
    }

    private ClusterNodeInfo findNodeByIpPort(List<ClusterNodeInfo> infos, String targetIp, String targetPort) {
        for (ClusterNodeInfo info : infos) {
            if (targetIp.equals(info.getIp()) && targetPort.equals(info.getPort())) {
                return info;
            }
        }
        return null;
    }

    private ClusterNodeInfo findNewNodeByPort(List<ClusterNodeInfo> infos, Set<String> baselineKeys, String targetPort) {
        for (ClusterNodeInfo info : infos) {
            if (targetPort.equals(info.getPort())
                    && !baselineKeys.contains(clusterKey(info.getIp(), info.getPort()))) {
                return info;
            }
        }
        return null;
    }

    private boolean clusterNodeIdExists(List<ClusterNodeInfo> infos, Integer clusterId) {
        if (clusterId == null) {
            return false;
        }
        for (ClusterNodeInfo info : infos) {
            if (clusterId.equals(info.getClusterId())) {
                return true;
            }
        }
        return false;
    }

    private String clusterKey(String ip, String port) {
        return safeValue(ip, "") + ":" + safeValue(port, "");
    }

    private boolean nodeExistsInClusterByIp(String targetIp) {
        try {
            ClusterInfo clusterInfo = iginxDao.getClusterInfo();
            if (clusterInfo.getIginxInfos() != null) {
                for (IginxInfo iginxInfo : clusterInfo.getIginxInfos()) {
                    if (targetIp.equals(iginxInfo.getIp())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void runCommand(List<String> command, DeployTaskState taskState) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // Sanitize PATH to prevent VS Code Server / Windows paths leaking into SSH sessions
        Map<String, String> env = builder.environment();
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.remove("VSCODE_GIT_ASKPASS_NODE");
        env.remove("VSCODE_GIT_ASKPASS_MAIN");
        env.remove("VSCODE_GIT_IPC_HANDLE");

        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            // Force UTF-8 decoding so script logs are stable across different server locales.
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String cleanLine = stripAnsi(line);
                appendLog(taskState, cleanLine);
                if (cleanLine.contains("[INFO]")) {
                    String step = cleanLine.replace("[INFO]", "").trim();
                    if (!step.isEmpty()) {
                        taskState.currentStep = step;
                    }
                }
                output.append(line).append('\n');
            }

            boolean done = process.waitFor(DEPLOY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new RuntimeException("命令执行超时");
            }

            if (process.exitValue() != 0) {
                throw new RuntimeException("命令执行失败: " + output.toString());
            }
        } catch (Exception e) {
            throw new RuntimeException("执行失败: " + e.getMessage(), e);
        }
    }

    private File resolveMetadataDirectory() {
        File metadataDir = scriptExecutionUtils.resolveOptionalResourceDirectory("udf/metadata");
        if (metadataDir != null) {
            return metadataDir;
        }
        throw new RuntimeException("UDF元数据目录不存在: 期望 resource.base-path 下的 udf/metadata");
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
        return value.trim();
    }

    private String safeValue(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String resolveSshPort(String port) {
        String normalized = safeValue(port, "22");
        try {
            int parsed = Integer.parseInt(normalized);
            if (parsed < 1 || parsed > 65535) {
                throw new RuntimeException("SSH端口必须在 1-65535 之间");
            }
            return String.valueOf(parsed);
        } catch (NumberFormatException e) {
            throw new RuntimeException("SSH端口必须是整数");
        }
    }

    private void appendLog(DeployTaskState taskState, String line) {
        if (line == null) {
            return;
        }
        synchronized (taskState.logs) {
            taskState.logs.add(line);
            if (taskState.logs.size() > MAX_LOG_LINES) {
                taskState.logs.remove(0);
            }
        }
    }

    private String stripAnsi(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private NodeDeployTaskStatus toTaskStatus(DeployTaskState state) {
        NodeDeployTaskStatus status = new NodeDeployTaskStatus();
        status.setTaskId(state.taskId);
        status.setStatus(state.status);
        status.setCurrentStep(state.currentStep);
        status.setCompleted(state.completed);
        status.setErrorMessage(state.errorMessage);
        synchronized (state.logs) {
            status.setLogs(new ArrayList<String>(state.logs));
        }
        return status;
    }

    private static class DeployTaskState {
        private final String taskId;
        private volatile String status;
        private volatile String currentStep;
        private volatile boolean completed;
        private volatile String errorMessage;
        private final List<String> logs = Collections.synchronizedList(new ArrayList<String>());

        private DeployTaskState(String taskId) {
            this.taskId = taskId;
            this.status = "RUNNING";
            this.currentStep = "准备中";
            this.completed = false;
        }
    }

    public static class ClusterNodeInfo {
        private Integer clusterId;
        private String ip;
        private String port;
        private String nodeType;

        public Integer getClusterId() { return clusterId; }
        public void setClusterId(Integer clusterId) { this.clusterId = clusterId; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    }
}
