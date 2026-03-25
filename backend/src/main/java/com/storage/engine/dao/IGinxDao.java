package com.storage.engine.dao;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.config.IGinxConnectionPool;
import com.storage.engine.constant.IGinxConstants;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class IGinxDao {
  private final IGinxConnectionPool connectionPool;

  private interface SessionAction<T> {
      T run(Session session) throws SessionException;
  }

  public IGinxDao(IGinxConnectionPool connectionPool) {
    this.connectionPool = connectionPool;
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
          "insert into %s(key, logicalPath, dataType, fileName, fileSize, fileFormat, createTime, isValid, knowledgeExtractStatus) " +
              "values (%d, '%s', '%s', '%s', %d, '%s', '%s', true, 'PENDING');",
              IGinxConstants.STORAGE_META_PATH,
              key, escapeSql(logicalPath), escapeSql(dataType), escapeSql(fileName),
              fileSize, escapeSql(fileFormat), escapeSql(createTime));
      executeSql(sql);
  }

  public void updateMetaKnowledgeStatus(long key, String status) {
      String safeStatus = status == null ? "" : escapeSql(status.trim().toUpperCase(Locale.ROOT));
      String sql = String.format(Locale.ROOT,
          "insert into %s(key, knowledgeExtractStatus) values (%d, '%s');",
          IGinxConstants.STORAGE_META_PATH, key, safeStatus);
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
      withRetry("insertColumnRecords", new SessionAction<Void>() {
          @Override
          public Void run(Session session) throws SessionException {
              session.insertColumnRecords(paths, timestamps, valuesList, dataTypeList, null);
              return null;
          }
      });
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
      return withRetryPreferSeed("getClusterInfo", new SessionAction<ClusterInfo>() {
          @Override
          public ClusterInfo run(Session session) throws SessionException {
              return session.getClusterInfo();
          }
      });
  }

  private String escapeSql(String value) {
      if (value == null) return "";
      return value.replace("'", "\\'");
  }

  public SessionExecuteSqlResult executeSql(String sql) {
      return withRetry("executeSql", new SessionAction<SessionExecuteSqlResult>() {
          @Override
          public SessionExecuteSqlResult run(Session session) throws SessionException {
              return session.executeSql(sql);
          }
      });
  }

  private <T> T withRetry(String opName, SessionAction<T> action) {
      int attempts = Math.max(connectionPool.getPoolSize(), 1);
      RuntimeException last = null;
      for (int i = 0; i < attempts; i++) {
          Session s = connectionPool.getNextSession();
          synchronized (s) {
              try {
                  return action.run(s);
              } catch (SessionException e) {
                  connectionPool.evictSession(s, opName + " failed: " + e.getMessage());
                  last = new RuntimeException("Failed to " + opName, e);
              }
          }
      }
      if (last != null) {
          throw last;
      }
      throw new RuntimeException("Failed to " + opName + ": no available IGinX session");
  }

  private <T> T withRetryPreferSeed(String opName, SessionAction<T> action) {
      Session seed = connectionPool.getSeedSessionOrAny();
      if (seed != null) {
          synchronized (seed) {
              try {
                  return action.run(seed);
              } catch (SessionException e) {
                  connectionPool.evictSession(seed, opName + " failed on seed: " + e.getMessage());
              }
          }
      }
      return withRetry(opName, action);
  }
}
