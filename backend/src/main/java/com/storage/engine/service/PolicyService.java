package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PolicyService {

    @Autowired
    private IGinxDao iginxDao;

    public Policy getPolicy() {
        try {
            SessionExecuteSqlResult result = iginxDao.getPolicy();
            if (result != null) {
                return parsePolicy(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Return default if not found
        Policy defaultPolicy = new Policy();
        defaultPolicy.setPolicy1(0);
        defaultPolicy.setPolicy1Desc("this is policy1");
        defaultPolicy.setPolicy2(0.0);
        defaultPolicy.setPolicy2Desc("this is policy2");
        defaultPolicy.setPolicy3(false);
        defaultPolicy.setPolicy3Desc("this is policy3");
        return defaultPolicy;
    }

    public synchronized Policy updatePolicy(Policy policy) {
        if (policy != null) {
            iginxDao.updatePolicy(
                policy.getPolicy1() != null ? policy.getPolicy1() : 0,
                policy.getPolicy2() != null ? policy.getPolicy2() : 0.0,
                policy.getPolicy3() != null ? policy.getPolicy3() : false
            );
            return policy;
        }
        return null;
    }

    private Policy parsePolicy(SessionExecuteSqlResult result) {
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        if (values.isEmpty()) return null;

        int policy1Idx = -1, policy2Idx = -1, policy3Idx = -1;
        int policy1DescIdx = -1, policy2DescIdx = -1, policy3DescIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("policy1")) policy1Idx = i;
            else if (path.endsWith("policy2")) policy2Idx = i;
            else if (path.endsWith("policy3")) policy3Idx = i;
            else if (path.endsWith("policy1Desc")) policy1DescIdx = i;
            else if (path.endsWith("policy2Desc")) policy2DescIdx = i;
            else if (path.endsWith("policy3Desc")) policy3DescIdx = i;
        }

        // We take the last row (latest config)
        List<Object> row = values.get(values.size() - 1);
        Policy policy = new Policy();

        if (policy1Idx != -1) policy.setPolicy1(getValueAsInt(row.get(policy1Idx)));
        if (policy2Idx != -1) policy.setPolicy2(getValueAsDouble(row.get(policy2Idx)));
        if (policy3Idx != -1) policy.setPolicy3(getValueAsBoolean(row.get(policy3Idx)));
        if (policy1DescIdx != -1) policy.setPolicy1Desc(getValueAsString(row.get(policy1DescIdx)));
        if (policy2DescIdx != -1) policy.setPolicy2Desc(getValueAsString(row.get(policy2DescIdx)));
        if (policy3DescIdx != -1) policy.setPolicy3Desc(getValueAsString(row.get(policy3DescIdx)));

        return policy;
    }

    private String getValueAsString(Object obj) {
        return obj == null ? null : new String((byte[])obj);
    }

    private Integer getValueAsInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Integer) return (Integer)obj;
        if (obj instanceof Long) return ((Long)obj).intValue();
        if (obj instanceof byte[]) {
             try {
                return Integer.parseInt(new String((byte[])obj));
             } catch (NumberFormatException e) {
                return 0;
             }
        }
        return 0;
    }

    private Double getValueAsDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Double) return (Double)obj;
        if (obj instanceof Float) return ((Float)obj).doubleValue();
        if (obj instanceof byte[]) {
             try {
                return Double.parseDouble(new String((byte[])obj));
             } catch (NumberFormatException e) {
                return 0.0;
             }
        }
        return 0.0;
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
