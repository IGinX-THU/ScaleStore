package com.storage.engine.dao;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.constant.IGinxConstants;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class IGinxDao {
  private final Session session;

  public IGinxDao(Session session) {
    this.session = session;
  }

  // User operations

  public void insertUser(long key, String username, String password, int type, String email, String phone, boolean isValid) {
      String sql = String.format(Locale.ROOT, "insert into %s(key, username, password, type, email, phone, isValid) values (%d, '%s', '%s', %d, '%s', '%s', %b);",
              IGinxConstants.USERS_PATH,
              key, username, password, type, email, phone, isValid);
      executeSql(sql);
  }

  public void updateUser(long key, String username, String password, int type, String email, String phone) {
      insertUser(key, username, password, type, email, phone, true);
  }

  public void deleteUser(long key) {
      String sql = String.format("insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.USERS_PATH, key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllUsers() {
      return executeSql("select * from " + IGinxConstants.USERS_PATH + ";");
  }

  public SessionExecuteSqlResult getUserById(long key) {
      return executeSql("select * from " + IGinxConstants.USERS_PATH + " where key = " + key + ";");
  }

  public long getMaxId() {
      SessionExecuteSqlResult result = executeSql("select last(username) from " + IGinxConstants.USERS_PATH + ";");
      if (result.getKeys() != null && result.getKeys().length > 0) {
          long[] keys = result.getKeys();
          return keys[keys.length - 1];
      }
      return -1;
  }

  // Node operations

  public void insertNode(long key, String name, String ip, String port, String description, String status, boolean isValid) {
      insertNode(key, name, ip, port, description, status, isValid, null);
  }

  public void insertNode(long key, String name, String ip, String port, String description, String status, boolean isValid, String deployDirectory) {
      if (deployDirectory != null && !deployDirectory.isEmpty()) {
          String sql = String.format("insert into %s(key, name, ip, port, description, status, isValid, deployDirectory) values (%d, '%s', '%s', '%s', '%s', '%s', %b, '%s');",
                  IGinxConstants.NODES_PATH,
                  key, name, ip, port, description, status, isValid, deployDirectory);
          executeSql(sql);
      } else {
          String sql = String.format("insert into %s(key, name, ip, port, description, status, isValid) values (%d, '%s', '%s', '%s', '%s', '%s', %b);",
                  IGinxConstants.NODES_PATH,
                  key, name, ip, port, description, status, isValid);
          executeSql(sql);
      }
  }

  public void updateNode(long key, String name, String ip, String port, String description, String status) {
      insertNode(key, name, ip, port, description, status, true, null);
  }

  public void updateNode(long key, String name, String ip, String port, String description, String status, String deployDirectory) {
      insertNode(key, name, ip, port, description, status, true, deployDirectory);
  }

  public void deleteNode(long key) {
      String sql = String.format("insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.NODES_PATH, key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllNodes() {
      try {
          return executeSql("select * from " + IGinxConstants.NODES_PATH + ";");
      } catch (RuntimeException e) {
          // sys.node path may not exist yet on a fresh IGinX instance
          return null;
      }
  }

  public SessionExecuteSqlResult getNodeById(long key) {
      return executeSql("select * from " + IGinxConstants.NODES_PATH + " where key = " + key + ";");
  }

  public long getMaxNodeId() {
      try {
          SessionExecuteSqlResult result = executeSql("select last(name) from " + IGinxConstants.NODES_PATH + ";");
          if (result.getKeys() != null && result.getKeys().length > 0) {
              long[] keys = result.getKeys();
              return keys[keys.length - 1];
          }
      } catch (RuntimeException e) {
          // sys.node path may not exist yet
      }
      return -1;
  }

  // Policy operations

  public void updatePolicy(int policy1, double policy2, boolean policy3) {
      String sql = String.format(Locale.ROOT, "insert into %s(key, policy1, policy2, policy3) values (0, %d, %f, %b);",
              IGinxConstants.POLICY_PATH, policy1, policy2, policy3);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getPolicy() {
      return executeSql("select * from " + IGinxConstants.POLICY_PATH + ";");
  }

  // ==================== Storage Metadata Operations ====================

  public void insertMeta(long key, String logicalPath, String dataType, String fileName,
                                            long fileSize, String fileFormat, String createTime) {
      String sql = String.format(Locale.ROOT,
              "insert into %s(key, logicalPath, dataType, fileName, fileSize, fileFormat, createTime, isValid) " +
              "values (%d, '%s', '%s', '%s', %d, '%s', '%s', true);",
              IGinxConstants.STORAGE_META_PATH,
              key, escapeSql(logicalPath), escapeSql(dataType), escapeSql(fileName),
              fileSize, escapeSql(fileFormat), escapeSql(createTime));
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllMeta() {
      return executeSql("select * from " + IGinxConstants.STORAGE_META_PATH + ";");
  }

  public long getMaxMetaId() {
      SessionExecuteSqlResult result = executeSql(
              "select last(logicalPath) from " + IGinxConstants.STORAGE_META_PATH + ";");
      if (result.getKeys() != null && result.getKeys().length > 0) {
          long[] keys = result.getKeys();
          return keys[keys.length - 1];
      }
      return -1;
  }

  public void deleteMeta(long key) {
      String sql = String.format("insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.STORAGE_META_PATH, key);
      executeSql(sql);
  }

  // ==================== Data Insertion using Programmatic API ====================

  public void insertColumnRecords(List<String> paths, long[] timestamps,
                                   Object[] valuesList, List<DataType> dataTypeList) {
      synchronized (session) {
          try {
              session.insertColumnRecords(paths, timestamps, valuesList, dataTypeList, null);
          } catch (SessionException e) {
              throw new RuntimeException("Failed to insertColumnRecords", e);
          }
      }
  }

  // ==================== Data Query Operations ====================

  public SessionExecuteSqlResult queryDataByPath(String pathPrefix) {
      return executeSql("select * from " + pathPrefix + ";");
  }

  public SessionExecuteSqlResult queryDataByPathWithLimit(String pathPrefix, int limit) {
      return executeSql("select * from " + pathPrefix + " limit " + limit + ";");
  }

  public void deleteDataByPath(String pathPrefix) {
      executeSql("delete from " + pathPrefix + ".*;");
  }

  // ==================== Cluster Info Operations ====================

  public ClusterInfo getClusterInfo() {
      synchronized (session) {
          try {
              return session.getClusterInfo();
          } catch (SessionException e) {
              throw new RuntimeException("Failed to get cluster info", e);
          }
      }
  }

  private String escapeSql(String value) {
      if (value == null) return "";
      return value.replace("'", "\\'");
  }

  public SessionExecuteSqlResult executeSql(String sql) {
      synchronized (session) {
          try {
              return session.executeSql(sql);
          } catch (SessionException e) {
              throw new RuntimeException("Failed to execute SQL: " + sql, e);
          }
      }
  }
}
