package com.storage.engine.dao;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;
import com.storage.engine.config.IGinxConnectionPool;
import com.storage.engine.constant.IGinxConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class IGinxDao {
    private static final Logger logger = LoggerFactory.getLogger(IGinxDao.class);
    private static final String[] EVICTABLE_ERROR_MARKERS = new String[] {
            "connection refused",
            "connect timed out",
            "connection timed out",
            "connection reset",
            "broken pipe",
            "no route to host",
            "network is unreachable",
            "socketexception",
            "ioexception",
            "transport",
            "session is closed",
            "session closed",
            "not open",
            "end of file",
            "channel inactive"
    };

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
      insertNode(key, name, ip, port, description, status, isValid, null, null);
  }

  public void insertNode(long key, String name, String ip, String port, String description, String status, boolean isValid, String deployDirectory) {
      insertNode(key, name, ip, port, description, status, isValid, deployDirectory, null);
  }

  public void insertNode(long key, String name, String ip, String port, String description,
                         String status, boolean isValid, String deployDirectory, Integer clusterId) {
      StringBuilder columns = new StringBuilder("key, name, ip, port, description, status, isValid");
      StringBuilder values = new StringBuilder(String.format(Locale.ROOT,
              "%d, '%s', '%s', '%s', '%s', '%s', %b",
              key, escapeSql(name), escapeSql(ip), escapeSql(port), escapeSql(description), escapeSql(status), isValid));

      if (deployDirectory != null && !deployDirectory.isEmpty()) {
          columns.append(", deployDirectory");
          values.append(String.format(Locale.ROOT, ", '%s'", escapeSql(deployDirectory)));
      }
      if (clusterId != null) {
          columns.append(", clusterId");
          values.append(String.format(Locale.ROOT, ", %d", clusterId));
      }

      String sql = String.format(Locale.ROOT,
              "insert into %s(%s) values (%s);",
              IGinxConstants.NODES_PATH,
              columns,
              values);
      executeSql(sql);
  }

  public void updateNode(long key, String name, String ip, String port, String description, String status) {
      insertNode(key, name, ip, port, description, status, true, null, null);
  }

  public void updateNode(long key, String name, String ip, String port, String description, String status, String deployDirectory) {
      insertNode(key, name, ip, port, description, status, true, deployDirectory, null);
  }

  public void updateNode(long key, String name, String ip, String port, String description,
                         String status, String deployDirectory, Integer clusterId) {
      insertNode(key, name, ip, port, description, status, true, deployDirectory, clusterId);
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

    public void updatePolicy(boolean extractionEnabled, long extractionScanIntervalMs, int metadataGraphMaxTriples) {
      String sql = String.format(
              Locale.ROOT,
                                                        "insert into %s(key, extractionEnabled, extractionScanIntervalMs, metadataGraphMaxTriples) values (0, %b, %d, %d);",
              IGinxConstants.POLICY_PATH,
              extractionEnabled,
                                                        extractionScanIntervalMs,
                                                        metadataGraphMaxTriples);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getPolicy() {
      return executeSql("select * from " + IGinxConstants.POLICY_PATH + ";");
  }

  public SessionExecuteSqlResult getTransformMetaExtractRows(int limit) {
      int safeLimit = Math.max(1, limit);
      try {
          String latestSql = "select * from transform order by key desc limit " + safeLimit + ";";
          return executeSql(latestSql);
      } catch (RuntimeException e) {
          // Fallback for engines that do not support ORDER BY on this path.
          String fallbackSql = "select * from transform limit " + safeLimit + ";";
          return executeSql(fallbackSql);
      }
  }

  // RESTful interface operations

  public void insertRestfulApi(long key,
                               String name,
                               String url,
                               String method,
                               String description,
                               String paramsExample,
                               String responseExample,
                               String curlExample,
                               boolean isValid) {
      String sql = String.format(
              Locale.ROOT,
              "insert into %s(key, name, url, method, description, paramsExample, responseExample, curlExample, isValid) values (%d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', %b);",
              IGinxConstants.INTERFACE_RESTFUL_PATH,
              key,
              escapeSql(name),
              escapeSql(url),
              escapeSql(method),
              escapeSql(description),
              escapeSql(paramsExample),
              escapeSql(responseExample),
              escapeSql(curlExample),
              isValid);
      executeSql(sql);
  }

  public void updateRestfulApi(long key,
                               String name,
                               String url,
                               String method,
                               String description,
                               String paramsExample,
                               String responseExample,
                               String curlExample,
                               boolean isValid) {
      insertRestfulApi(key, name, url, method, description, paramsExample, responseExample, curlExample, isValid);
  }

  public void deleteRestfulApi(long key) {
      String sql = String.format(Locale.ROOT,
              "insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.INTERFACE_RESTFUL_PATH,
              key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllRestfulApis() {
      return executeSql("select * from " + IGinxConstants.INTERFACE_RESTFUL_PATH + ";");
  }

  public SessionExecuteSqlResult getRestfulApiById(long key) {
      return executeSql("select * from " + IGinxConstants.INTERFACE_RESTFUL_PATH + " where key = " + key + ";");
  }

  public long getMaxRestfulApiId() {
      try {
          SessionExecuteSqlResult result = executeSql("select last(name) from " + IGinxConstants.INTERFACE_RESTFUL_PATH + ";");
          if (result.getKeys() != null && result.getKeys().length > 0) {
              long[] keys = result.getKeys();
              return keys[keys.length - 1];
          }
      } catch (RuntimeException e) {
          // interface.restful path may not exist on fresh deployments
      }
      return -1;
  }

  // Java gRPC interface operations

  public void insertJavaGrpcApi(long key,
                                String name,
                                String url,
                                String method,
                                String description,
                                String paramsExample,
                                String responseExample,
                                String invokeExample,
                                boolean isValid) {
      String sql = String.format(
              Locale.ROOT,
              "insert into %s(key, name, url, method, description, paramsExample, responseExample, curlExample, isValid) values (%d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', %b);",
              IGinxConstants.INTERFACE_JAVA_GRPC_PATH,
              key,
              escapeSql(name),
              escapeSql(url),
              escapeSql(method),
              escapeSql(description),
              escapeSql(paramsExample),
              escapeSql(responseExample),
              escapeSql(invokeExample),
              isValid);
      executeSql(sql);
  }

  public void updateJavaGrpcApi(long key,
                                String name,
                                String url,
                                String method,
                                String description,
                                String paramsExample,
                                String responseExample,
                                String invokeExample,
                                boolean isValid) {
      insertJavaGrpcApi(key, name, url, method, description, paramsExample, responseExample, invokeExample, isValid);
  }

  public void deleteJavaGrpcApi(long key) {
      String sql = String.format(Locale.ROOT,
              "insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.INTERFACE_JAVA_GRPC_PATH,
              key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllJavaGrpcApis() {
      try {
          return executeSql("select * from " + IGinxConstants.INTERFACE_JAVA_GRPC_PATH + ";");
      } catch (RuntimeException e) {
          return null;
      }
  }

  public SessionExecuteSqlResult getJavaGrpcApiById(long key) {
      try {
          return executeSql("select * from " + IGinxConstants.INTERFACE_JAVA_GRPC_PATH + " where key = " + key + ";");
      } catch (RuntimeException e) {
          return null;
      }
  }

  public long getMaxJavaGrpcApiId() {
      try {
          SessionExecuteSqlResult result = executeSql("select last(name) from " + IGinxConstants.INTERFACE_JAVA_GRPC_PATH + ";");
          if (result.getKeys() != null && result.getKeys().length > 0) {
              long[] keys = result.getKeys();
              return keys[keys.length - 1];
          }
      } catch (RuntimeException e) {
          // interface.java_grpc path may not exist on fresh deployments
      }
      return -1;
  }

  // Python gRPC interface operations

  public void insertPythonGrpcApi(long key,
                                  String name,
                                  String url,
                                  String method,
                                  String description,
                                  String paramsExample,
                                  String responseExample,
                                  String invokeExample,
                                  boolean isValid) {
      String sql = String.format(
              Locale.ROOT,
              "insert into %s(key, name, url, method, description, paramsExample, responseExample, curlExample, isValid) values (%d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', %b);",
              IGinxConstants.INTERFACE_PYTHON_GRPC_PATH,
              key,
              escapeSql(name),
              escapeSql(url),
              escapeSql(method),
              escapeSql(description),
              escapeSql(paramsExample),
              escapeSql(responseExample),
              escapeSql(invokeExample),
              isValid);
      executeSql(sql);
  }

  public void updatePythonGrpcApi(long key,
                                  String name,
                                  String url,
                                  String method,
                                  String description,
                                  String paramsExample,
                                  String responseExample,
                                  String invokeExample,
                                  boolean isValid) {
      insertPythonGrpcApi(key, name, url, method, description, paramsExample, responseExample, invokeExample, isValid);
  }

  public void deletePythonGrpcApi(long key) {
      String sql = String.format(Locale.ROOT,
              "insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.INTERFACE_PYTHON_GRPC_PATH,
              key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllPythonGrpcApis() {
      try {
          return executeSql("select * from " + IGinxConstants.INTERFACE_PYTHON_GRPC_PATH + ";");
      } catch (RuntimeException e) {
          return null;
      }
  }

  public SessionExecuteSqlResult getPythonGrpcApiById(long key) {
      try {
          return executeSql("select * from " + IGinxConstants.INTERFACE_PYTHON_GRPC_PATH + " where key = " + key + ";");
      } catch (RuntimeException e) {
          return null;
      }
  }

  public long getMaxPythonGrpcApiId() {
      try {
          SessionExecuteSqlResult result = executeSql("select last(name) from " + IGinxConstants.INTERFACE_PYTHON_GRPC_PATH + ";");
          if (result.getKeys() != null && result.getKeys().length > 0) {
              long[] keys = result.getKeys();
              return keys[keys.length - 1];
          }
      } catch (RuntimeException e) {
          // interface.python_grpc path may not exist on fresh deployments
      }
      return -1;
  }

  // ==================== Storage Metadata Operations ====================

  public void insertMeta(long key, String logicalPath, String dataType, String fileName,
             String contentPath, long fileSize, String fileFormat, String createTime) {
      String sql = String.format(
              Locale.ROOT,
          "insert into %s(key, logicalPath, dataType, fileName, contentPath, fileSize, fileFormat, createTime, isValid, knowledgeExtractStatus) values (%d, '%s', '%s', '%s', '%s', %d, '%s', '%s', true, 'PENDING');",
              IGinxConstants.STORAGE_META_PATH,
              key,
              escapeSql(logicalPath),
              escapeSql(dataType),
              escapeSql(fileName),
          escapeSqlKeepBackslash(contentPath),
              fileSize,
              escapeSql(fileFormat),
              escapeSql(createTime));
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
      String queryPath = normalizePathForQuery(pathPrefix);
      return executeSql("select * from " + queryPath + ";");
  }

  public SessionExecuteSqlResult queryDataByPathWithLimit(String pathPrefix, int limit) {
      String queryPath = normalizePathForQuery(pathPrefix);
      return executeSql("select * from " + queryPath + " limit " + limit + ";");
  }

  public void deleteDataByPath(String pathPrefix) {
      String queryPath = normalizePathForQuery(pathPrefix);
      executeSql("delete from " + queryPath + ".*;");
  }

  private String normalizePathForQuery(String pathPrefix) {
      String path = pathPrefix == null ? "" : pathPrefix.trim();
      while (path.contains("\\\\")) {
          path = path.replace("\\\\", "\\");
      }
      return path;
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
      return value.replace("\\", "\\\\").replace("'", "''");
  }

  private String escapeSqlKeepBackslash(String value) {
      if (value == null) return "";
      return value.replace("'", "''");
  }

  public SessionExecuteSqlResult executeSql(String sql) {
      logger.info("[IGinX-SQL] {}", sql);
      return withRetry("executeSql", new SessionAction<SessionExecuteSqlResult>() {
          @Override
          public SessionExecuteSqlResult run(Session session) throws SessionException {
              return session.executeSql(sql);
          }
      });
  }

  /**
   * Execute SQL by preferring a target IGinX endpoint first, then fallback to other nodes.
   * This is used for endpoint-sensitive operations (for example filesystem external source registration).
   */
  public SessionExecuteSqlResult executeSqlPreferEndpoint(String sql, String preferredIp, Integer preferredPort) {
      logger.info("[IGinX-SQL][PreferEndpoint {}:{}] {}",
              preferredIp,
              preferredPort,
              sql);

      String preferredPortText = preferredPort == null ? null : String.valueOf(preferredPort.intValue());
      List<Session> orderedSessions = connectionPool.getSessionsPrioritized(preferredIp, preferredPortText);
      if (orderedSessions.isEmpty()) {
          throw new RuntimeException("Failed to executeSql: no available IGinX session");
      }

      RuntimeException last = null;
      for (Session session : orderedSessions) {
          synchronized (session) {
              try {
                  return session.executeSql(sql);
              } catch (SessionException e) {
                  if (shouldEvictSession(e)) {
                      connectionPool.evictSession(session, "executeSqlPreferEndpoint failed: " + e.getMessage());
                  }
                  last = new RuntimeException("Failed to executeSql on one IGinX endpoint: " + e.getMessage(), e);
              }
          }
      }

      if (last != null) {
          throw last;
      }
      throw new RuntimeException("Failed to executeSql: no available IGinX session");
  }

  private boolean shouldEvictSession(SessionException e) {
      String details = flattenExceptionMessage(e).toLowerCase(Locale.ROOT);
      if (details.isEmpty()) {
          return true;
      }
      for (String marker : EVICTABLE_ERROR_MARKERS) {
          if (details.contains(marker)) {
              return true;
          }
      }
      // Default to keeping the session for SQL/business errors so one failed request
      // cannot collapse the whole pool.
      return false;
  }

  private String flattenExceptionMessage(Throwable throwable) {
      StringBuilder sb = new StringBuilder();
      Throwable current = throwable;
      while (current != null) {
          String message = current.getMessage();
          if (message != null) {
              String trimmed = message.trim();
              if (!trimmed.isEmpty()) {
                  if (sb.length() > 0) {
                      sb.append(" | ");
                  }
                  sb.append(trimmed);
              }
          }
          current = current.getCause();
      }
      return sb.toString();
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
                  if (shouldEvictSession(e)) {
                      connectionPool.evictSession(s, opName + " failed: " + e.getMessage());
                      last = new RuntimeException("Failed to " + opName, e);
                  } else {
                      throw new RuntimeException("Failed to " + opName + ": " + e.getMessage(), e);
                  }
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
                  if (shouldEvictSession(e)) {
                      connectionPool.evictSession(seed, opName + " failed on seed: " + e.getMessage());
                  } else {
                      throw new RuntimeException("Failed to " + opName + ": " + e.getMessage(), e);
                  }
              }
          }
      }
      return withRetry(opName, action);
  }
}
