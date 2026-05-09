package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.config.IGinxConnectionPool;
import com.storage.engine.constant.ResultCode;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.Node;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NodeService {

    @Autowired
    private IGinxDao iginxDao;

    @Autowired
    private NodeDeployService nodeDeployService;

    @Autowired
    private IGinxConnectionPool connectionPool;

    /**
     * Get all nodes by merging show cluster info (live) with sys.node (metadata).
     * Uses ip:port as the "foreign key" to link cluster nodes with their stored name/description.
     * Always uses clusterId as the node id.
     *
     * Display IP is the real cluster-reported IP from show cluster info.
     */
    public List<Node> getAllNodes() {
        List<Node> merged = new ArrayList<Node>();
        try {
            List<Node> metadataNodes = getMetadataNodes();
            Map<Integer, Node> metadataByClusterId = new HashMap<Integer, Node>();
            Map<String, Node> metadataByIpPort = new HashMap<String, Node>();
            Map<String, Node> metadataByIp = new HashMap<String, Node>();
            for (Node meta : metadataNodes) {
                if (meta.getClusterId() != null && !metadataByClusterId.containsKey(meta.getClusterId())) {
                    metadataByClusterId.put(meta.getClusterId(), meta);
                }
                if (!isBlank(meta.getIp()) && !isBlank(meta.getPort())) {
                    metadataByIpPort.put(meta.getIp() + ":" + meta.getPort(), meta);
                }
                if (!isBlank(meta.getIp()) && !metadataByIp.containsKey(meta.getIp())) {
                    metadataByIp.put(meta.getIp(), meta);
                }
            }

            List<NodeDeployService.ClusterNodeInfo> clusterInfos = nodeDeployService.getClusterNodeInfos();
            for (NodeDeployService.ClusterNodeInfo info : clusterInfos) {
                Node meta = metadataByClusterId.get(info.getClusterId());
                String key = info.getIp() + ":" + info.getPort();
                if (meta == null) {
                    meta = metadataByIpPort.get(key);
                }
                if (meta == null) {
                    meta = metadataByIp.get(info.getIp());
                }

                Node node = new Node();
                // Always use cluster id as the canonical node id
                node.setId(info.getClusterId());
                node.setClusterId(info.getClusterId());
                // Always display the real cluster-reported IP in UI.
                node.setIp(!isBlank(info.getIp())
                    ? info.getIp()
                    : (meta != null ? defaultString(meta.getIp()) : ""));
                node.setPort(info.getPort());
                node.setName(meta != null && !isBlank(meta.getName())
                        ? meta.getName()
                        : "iginx-" + info.getClusterId());
                node.setDescription(meta != null ? defaultString(meta.getDescription()) : "");
                node.setDeployDirectory(meta != null ? defaultString(meta.getDeployDirectory()) : "");
                node.setStatus("ONLINE");
                node.setIsValid(true);
                node.setNodeType(info.getNodeType() != null ? info.getNodeType() : "iginx");
                merged.add(node);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return merged;
    }

    /**
     * Get a single node by cluster id from the merged view.
     */
    public Node getNodeById(Integer clusterId) {
        List<Node> allNodes = getAllNodes();
        for (Node node : allNodes) {
            if (node.getId() != null && node.getId().equals(clusterId)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Create a new node asynchronously (deploy via SSH).
     */
    public synchronized NodeDeployTaskStatus createNodeAsync(NodeDeployRequest request) {
        List<Node> currentNodes = getAllNodes();
        if (currentNodes.size() >= 24) {
            throw new RuntimeException(ResultCode.NODE_LIMIT_EXCEEDED.getMessage());
        }

        validateCreateRequest(request);

        final String nodeName = request.getName().trim();
        final String nodePort = isBlank(request.getPort()) ? "6888" : request.getPort().trim();
        final String nodeDesc = defaultString(request.getDescription());
        final String nodeDeployDir = defaultString(request.getDeployDirectory());

        return nodeDeployService.startDeployTask(request, 0, nodePort, new Runnable() {
            @Override
            public void run() {
                String nodeIp = request.getIp() == null ? "" : request.getIp().trim();
                Integer detectedClusterId = request.getId();
                long maxId = iginxDao.getMaxNodeId();
                iginxDao.insertNode(maxId + 1, nodeName, nodeIp, nodePort, nodeDesc,
                        "ONLINE", true, nodeDeployDir, detectedClusterId);
                connectionPool.addNode(nodeIp, nodePort);
            }
        });
    }

    /**
     * Update a node's name and description only (linked by ip:port foreign key).
     * The clusterId is used to find the node in show cluster info,
     * then the corresponding sys.node entry is found/created by ip:port.
     */
    public Node updateNode(Integer clusterId, String name, String description) {
        // Find the cluster node by clusterId
        List<NodeDeployService.ClusterNodeInfo> clusterInfos = nodeDeployService.getClusterNodeInfos();
        NodeDeployService.ClusterNodeInfo target = null;
        for (NodeDeployService.ClusterNodeInfo info : clusterInfos) {
            if (info.getClusterId().equals(clusterId)) {
                target = info;
                break;
            }
        }
        if (target == null) {
            return null;
        }

        // Find existing sys.node entry by ip:port (foreign key)
        List<Node> metaNodes = getMetadataNodes();
        Node existingMeta = null;
        for (Node meta : metaNodes) {
            if (target.getIp().equals(meta.getIp()) && target.getPort().equals(meta.getPort())) {
                existingMeta = meta;
                break;
            }
        }

        if (existingMeta != null) {
            // Update existing sys.node entry
            iginxDao.updateNode(existingMeta.getId(), name, existingMeta.getIp(), target.getPort(),
                defaultString(description), "ONLINE",
                defaultString(existingMeta.getDeployDirectory()), existingMeta.getClusterId());
        } else {
            // Create new sys.node entry for this cluster node
            long newKey = iginxDao.getMaxNodeId() + 1;
            iginxDao.insertNode(newKey, name, target.getIp(), target.getPort(),
                defaultString(description), "ONLINE", true, "", target.getClusterId());
        }

        Node result = new Node();
        result.setId(clusterId);
        result.setName(name);
        result.setIp(target.getIp());
        result.setPort(target.getPort());
        result.setDescription(description);
        result.setStatus("ONLINE");
        result.setIsValid(true);
        return result;
    }

    /**
     * Delete (stop) a node asynchronously via SSH stop script.
     */
    public synchronized NodeDeployTaskStatus deleteNodeAsync(Integer clusterId,
            String sshUsername, String sshPort, String sshPassword, String deployDirectory) {
        final Node node = getNodeById(clusterId);
        if (node == null) {
            throw new RuntimeException("节点不存在");
        }
        ensureNodeCanBeRemoved(node);

        final Node metadataNode = findMetadataNodeForCluster(clusterId, node.getIp(), node.getPort());
        final String clusterVisibleIp = node.getIp();
        final String nodePort = node.getPort();
        final String sshTargetIp = metadataNode != null && !isBlank(metadataNode.getIp())
                ? metadataNode.getIp()
                : clusterVisibleIp;
        final Integer metadataNodeKey = metadataNode != null ? metadataNode.getId() : null;

        return nodeDeployService.startStopTask(
            sshTargetIp, nodePort, sshUsername, sshPort, sshPassword, deployDirectory, clusterId,
                new Runnable() {
                    @Override
                    public void run() {
                        if (metadataNodeKey != null) {
                            iginxDao.deleteNode(metadataNodeKey.longValue());
                        } else {
                            cleanupSysNode(sshTargetIp, nodePort);
                        }
                        connectionPool.removeNode(clusterVisibleIp, nodePort);
                        if (!isBlank(sshTargetIp) && !sshTargetIp.equals(clusterVisibleIp)) {
                            connectionPool.removeNode(sshTargetIp, nodePort);
                        }
                    }
                });
    }

    /**
     * Simple soft-delete of sys.node entry (mark isValid=false).
     */
    public boolean deleteNode(Integer clusterId) {
        Node node = getNodeById(clusterId);
        if (node == null) {
            return false;
        }
        ensureNodeCanBeRemoved(node);
        cleanupSysNode(clusterId);
        return true;
    }

    public NodeDeployTaskStatus getDeployTaskStatus(String taskId) {
        return nodeDeployService.getTaskStatus(taskId);
    }

    // ==================== Internal Helpers ====================

    /**
     * Clean up sys.node entry by matching ip:port.
     */
    private void cleanupSysNode(String ip, String port) {
        try {
            List<Node> metaNodes = getMetadataNodes();
            for (Node meta : metaNodes) {
                if (ip.equals(meta.getIp()) && port.equals(meta.getPort())) {
                    iginxDao.deleteNode(meta.getId());
                    break;
                }
            }
        } catch (Exception e) {
            // Best effort cleanup
            e.printStackTrace();
        }
    }

    /**
     * Clean up sys.node entry by cluster id (find ip:port first).
     */
    private void cleanupSysNode(Integer clusterId) {
        try {
            List<NodeDeployService.ClusterNodeInfo> infos = nodeDeployService.getClusterNodeInfos();
            for (NodeDeployService.ClusterNodeInfo info : infos) {
                if (info.getClusterId().equals(clusterId)) {
                    cleanupSysNode(info.getIp(), info.getPort());
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<Node> getMetadataNodes() {
        SessionExecuteSqlResult result = iginxDao.getAllNodes();
        if (result == null) {
            return new ArrayList<Node>();
        }
        return parseNodes(result);
    }

    private Node findMetadataNodeForCluster(Integer clusterId, String clusterIp, String clusterPort) {
        List<Node> metadataNodes = getMetadataNodes();
        if (clusterId != null) {
            for (Node meta : metadataNodes) {
                if (meta.getClusterId() != null && clusterId.equals(meta.getClusterId())) {
                    return meta;
                }
            }
        }
        if (!isBlank(clusterIp) && !isBlank(clusterPort)) {
            for (Node meta : metadataNodes) {
                if (clusterIp.equals(meta.getIp()) && clusterPort.equals(meta.getPort())) {
                    return meta;
                }
            }
        }
        return null;
    }

    private List<Node> parseNodes(SessionExecuteSqlResult result) {
        List<Node> nodes = new ArrayList<Node>();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        int nodenameIdx = -1, ipIdx = -1, portIdx = -1, descIdx = -1,
                isValidIdx = -1, statusIdx = -1, deployDirIdx = -1, clusterIdIdx = -1;
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("name")) nodenameIdx = i;
            else if (path.endsWith("ip")) ipIdx = i;
            else if (path.endsWith("port")) portIdx = i;
            else if (path.endsWith("description")) descIdx = i;
            else if (path.endsWith("status")) statusIdx = i;
            else if (path.endsWith("isValid")) isValidIdx = i;
            else if (path.endsWith("deployDirectory")) deployDirIdx = i;
            else if (path.endsWith("clusterId")) clusterIdIdx = i;
        }

        for (int i = 0; i < keys.length; i++) {
            Node node = new Node();
            node.setId((int) keys[i]);
            List<Object> row = values.get(i);

            if (nodenameIdx != -1) node.setName(getValueAsString(row.get(nodenameIdx)));
            if (ipIdx != -1) node.setIp(getValueAsString(row.get(ipIdx)));
            if (portIdx != -1) node.setPort(getValueAsString(row.get(portIdx)));
            if (descIdx != -1) node.setDescription(getValueAsString(row.get(descIdx)));
            if (statusIdx != -1) node.setStatus(getValueAsString(row.get(statusIdx)));
            if (deployDirIdx != -1) node.setDeployDirectory(getValueAsString(row.get(deployDirIdx)));
            if (clusterIdIdx != -1) node.setClusterId(getValueAsInteger(row.get(clusterIdIdx)));

            boolean isValid = true;
            if (isValidIdx != -1) {
                isValid = getValueAsBoolean(row.get(isValidIdx));
            }
            node.setIsValid(isValid);

            if (isValid) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private String getValueAsString(Object obj) {
        return obj == null ? null : new String((byte[])obj);
    }

    private Boolean getValueAsBoolean(Object obj) {
       if (obj == null) return false;
       if (obj instanceof Boolean) return (Boolean)obj;
       if (obj instanceof byte[]) {
           return Boolean.parseBoolean(new String((byte[])obj));
       }
       return false;
    }

    private Integer getValueAsInteger(Object obj) {
        if (obj == null) return null;
        try {
            if (obj instanceof Number) {
                return ((Number) obj).intValue();
            }
            if (obj instanceof byte[]) {
                return Integer.parseInt(new String((byte[]) obj).trim());
            }
            return Integer.parseInt(String.valueOf(obj).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void validateCreateRequest(NodeDeployRequest request) {
        if (request == null) {
            throw new RuntimeException("请求体不能为空");
        }
        if (isBlank(request.getName())) {
            throw new RuntimeException("节点名不能为空");
        }
        if (isBlank(request.getIp())) {
            throw new RuntimeException("节点IP不能为空");
        }
        if (isBlank(request.getSshUsername())) {
            throw new RuntimeException("SSH用户名不能为空");
        }
        if (isBlank(request.getSshPassword())) {
            throw new RuntimeException("SSH密码不能为空");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private void ensureNodeCanBeRemoved(Node node) {
        if (node == null || isBlank(node.getIp()) || isBlank(node.getPort())) {
            return;
        }
        if (connectionPool.isBootstrapNode(node.getIp(), node.getPort())) {
            throw new RuntimeException("该节点是 WebServer 启动时已存在的节点，不能移除");
        }
    }
}
