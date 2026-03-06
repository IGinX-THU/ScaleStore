package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.thrift.IginxInfo;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private final Map<String, DeployTaskState> taskStates = new ConcurrentHashMap<String, DeployTaskState>();
    private final ExecutorService deployExecutor = Executors.newCachedThreadPool();

    @Autowired
    private IGinxDao iginxDao;

    @Value("${deploy.iginx.script-path:/home/ubuntu/ScaleStore/backend/scripts/deploy_iginx.sh}")
    private String deployScriptPath;

    @Value("${deploy.iginx.stop-script-path:/home/ubuntu/ScaleStore/backend/scripts/stop_iginx.sh}")
    private String stopScriptPath;

    @Value("${deploy.iginx.default-package-path:/home/ubuntu/IGinX-FastDeploy-0.8.0.tar.gz}")
    private String defaultPackagePath;

    @Value("${deploy.iginx.default-zk-connection:127.0.0.1:2181}")
    private String defaultZkConnection;

    @Value("${deploy.iginx.default-directory:/opt/iginx}")
    private String defaultDeployDirectory;

    // ==================== Deploy (Add Node) ====================

    public NodeDeployTaskStatus startDeployTask(final NodeDeployRequest request, final int nodeId,
                                                final String nodePort, final Runnable successAction) {
        String targetIp = required(request.getIp(), "节点IP不能为空");
        String username = required(request.getSshUsername(), "SSH用户名不能为空");
        String password = required(request.getSshPassword(), "SSH密码不能为空");

        String packagePath = safeValue(request.getPackagePath(), defaultPackagePath);
        File packageFile = new File(packagePath);
        if (!packageFile.exists()) {
            throw new RuntimeException("部署包不存在: " + packagePath);
        }

        String deployDir = safeValue(request.getDeployDirectory(), defaultDeployDirectory);
        String zkConnection = safeValue(request.getZookeeperConnectionString(), defaultZkConnection);

        File scriptFile = new File(deployScriptPath);
        if (!scriptFile.exists()) {
            throw new RuntimeException("部署脚本不存在: " + deployScriptPath);
        }

        final List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add(scriptFile.getAbsolutePath());
        command.add(targetIp);
        command.add(username);
        command.add(password);
        command.add(packageFile.getAbsolutePath());
        command.add(deployDir);
        command.add(zkConnection);

        final String taskId = UUID.randomUUID().toString();
        final DeployTaskState taskState = new DeployTaskState(taskId);
        taskStates.put(taskId, taskState);

        deployExecutor.submit(new Runnable() {
            @Override
            public void run() {
                taskState.currentStep = "正在执行部署脚本";
                appendLog(taskState, "[STEP] 开始执行部署脚本");
                try {
                    runCommand(command, taskState);
                    taskState.currentStep = "正在校验 show cluster info";
                    appendLog(taskState, "[STEP] 开始校验节点是否加入集群");
                    waitForNodeJoinCluster(request.getIp(), nodePort, taskState);
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

    public NodeDeployTaskStatus startStopTask(final String targetIp, final String username,
                                              final String password, final String deployDirectory,
                                              final Runnable successAction) {
        required(targetIp, "节点IP不能为空");
        required(username, "SSH用户名不能为空");
        required(password, "SSH密码不能为空");

        String deployDir = safeValue(deployDirectory, defaultDeployDirectory);

        File scriptFile = new File(stopScriptPath);
        if (!scriptFile.exists()) {
            throw new RuntimeException("停止脚本不存在: " + stopScriptPath);
        }

        final List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add(scriptFile.getAbsolutePath());
        command.add(targetIp);
        command.add(username);
        command.add(password);
        command.add(deployDir);

        final String taskId = UUID.randomUUID().toString();
        final DeployTaskState taskState = new DeployTaskState(taskId);
        taskStates.put(taskId, taskState);

        deployExecutor.submit(new Runnable() {
            @Override
            public void run() {
                taskState.currentStep = "正在执行停止脚本";
                appendLog(taskState, "[STEP] 开始执行停止脚本");
                try {
                    runCommand(command, taskState);
                    taskState.currentStep = "正在校验节点已移除";
                    appendLog(taskState, "[STEP] 正在校验节点是否已从集群中移除");
                    waitForNodeLeaveCluster(targetIp, taskState);
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

    private void waitForNodeJoinCluster(String targetIp, String nodePort, DeployTaskState taskState) {
        String expectedPort = safeValue(nodePort, "6888");
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CLUSTER_WAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (nodeExistsInClusterInfo(targetIp, expectedPort)) {
                return;
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

    private void waitForNodeLeaveCluster(String targetIp, DeployTaskState taskState) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CLUSTER_WAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (!nodeExistsInClusterByIp(targetIp)) {
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待节点离开集群被中断", e);
            }
            appendLog(taskState, "[WAIT] show cluster info 中仍存在 " + targetIp);
        }
        throw new RuntimeException("停止脚本执行成功，但在 show cluster info 中仍检测到节点: " + targetIp);
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
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
