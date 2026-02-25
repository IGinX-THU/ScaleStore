package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.Node;
import com.storage.engine.constant.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class NodeService {

    @Autowired
    private IGinxDao iginxDao;

    public List<Node> getAllNodes() {
        List<Node> nodes = new ArrayList<>();
        try {
            SessionExecuteSqlResult result = iginxDao.getAllNodes();
            if (result != null) {
                nodes = parseNodes(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return nodes;
    }

    public synchronized Node createNode(Node node) {
        List<Node> currentNodes = getAllNodes();
        if (currentNodes.size() >= 24) {
            throw new RuntimeException(ResultCode.NODE_LIMIT_EXCEEDED.getMessage());
        }

        if (node.getId() == null) {
            long maxId = iginxDao.getMaxNodeId();
            node.setId((int) (maxId + 1));
        }
        node.setIsValid(true);
        iginxDao.insertNode(node.getId(), node.getName(), node.getIp(), node.getPort(), node.getDescription(), true);
        return node;
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
            iginxDao.updateNode(id, node.getName(), node.getIp(), node.getPort(), node.getDescription());
            node.setIsValid(true);
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
}
