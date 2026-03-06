package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.Node;
import com.storage.engine.model.NodeDeployRequest;
import com.storage.engine.model.NodeDeployTaskStatus;
import com.storage.engine.constant.ResultCode;
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

    public List<Node> getAllNodes() {
        List<Node> merged = new ArrayList<Node>();
        try {
            List<Node> metadataNodes = getMetadataNodes();
            Map<String, Node> metadataByIpPort = new HashMap<String, Node>();
            Map<String, Node> metadataByIp = new HashMap<String, Node>();
            for (Node meta : metadataNodes) {
                if (!isBlank(meta.getIp()) && !isBlank(meta.getPort())) {
                    metadataByIpPort.put(meta.getIp() + ":" + meta.getPort(), meta);
                }
                if (!isBlank(meta.getIp()) && !metadataByIp.containsKey(meta.getIp())) {
                    metadataByIp.put(meta.getIp(), meta);
                }
            }

            List<NodeDeployService.ClusterNodeInfo> clusterInfos = nodeDeployService.getClusterNodeInfos();
            for (NodeDeployService.ClusterNodeInfo info : clusterInfos) {
                String key = info.getIp() + ":" + info.getPort();
                Node meta = metadataByIpPort.get(key);
                if (meta == null) {
                    meta = metadataByIp.get(info.getIp());
                }

                Node node = new Node();
                node.setId(meta != null && meta.getId() != null ? meta.getId() : info.getClusterId());
                node.setIp(info.getIp());
                node.setPort(info.getPort());
                node.setName(meta != null && !isBlank(meta.getName())
                        ? meta.getName()
                        : "iginx-" + info.getClusterId());
                node.setDescription(meta != null ? defaultString(meta.getDescription()) : "");
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

    public synchronized NodeDeployTaskStatus createNodeAsync(NodeDeployRequest request) {
        List<Node> currentNodes = getAllNodes();
        if (currentNodes.size() >= 24) {
            throw new RuntimeException(ResultCode.NODE_LIMIT_EXCEEDED.getMessage());
        }

        validateCreateRequest(request);

        Node node = new Node();
        node.setName(request.getName().trim());
        node.setIp(request.getIp().trim());
        node.setPort(isBlank(request.getPort()) ? "6888" : request.getPort().trim());
        node.setDescription(request.getDescription());

        if (node.getId() == null) {
            long maxId = iginxDao.getMaxNodeId();
            node.setId((int) (maxId + 1));
        }

        return nodeDeployService.startDeployTask(request, node.getId(), node.getPort(), new Runnable() {
            @Override
            public void run() {
                iginxDao.insertNode(node.getId(), node.getName(), node.getIp(), node.getPort(),
                        defaultString(node.getDescription()), "ONLINE", true);
            }
        });
    }

    public NodeDeployTaskStatus getDeployTaskStatus(String taskId) {
        return nodeDeployService.getTaskStatus(taskId);
    }

    public Node getNodeById(Integer id) {
        try {
            SessionExecuteSqlResult result = iginxDao.getNodeById(id);
            List<Node> nodes = parseNodes(result);
            if (!nodes.isEmpty()) {
                return nodes.get(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public Node updateNode(Integer id, Node node) {
        Node existing = getNodeById(id);
        if (existing != null) {
            node.setId(id);
            String status = node.getStatus() == null || node.getStatus().trim().isEmpty()
                ? defaultString(existing.getStatus())
                : node.getStatus();
            iginxDao.updateNode(id, node.getName(), node.getIp(), node.getPort(),
                defaultString(node.getDescription()), status);
            node.setIsValid(true);
            node.setStatus(status);
            return node;
        }
        return null;
    }

    public boolean deleteNode(Integer id) {
        Node existing = getNodeById(id);
        if (existing != null) {
            iginxDao.deleteNode(id);
            return true;
        }
        return false;
    }

    private List<Node> parseNodes(SessionExecuteSqlResult result) {
        List<Node> nodes = new ArrayList<>();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        int nodenameIdx = -1, ipIdx = -1, portIdx = -1, descIdx = -1, isValidIdx = -1, statusIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("name")) nodenameIdx = i;
            else if (path.endsWith("ip")) ipIdx = i;
            else if (path.endsWith("port")) portIdx = i;
            else if (path.endsWith("description")) descIdx = i;
            else if (path.endsWith("status")) statusIdx = i;
            else if (path.endsWith("isValid")) isValidIdx = i;
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

    private List<Node> getMetadataNodes() {
        SessionExecuteSqlResult result = iginxDao.getAllNodes();
        if (result == null) {
            return new ArrayList<Node>();
        }
        return parseNodes(result);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
