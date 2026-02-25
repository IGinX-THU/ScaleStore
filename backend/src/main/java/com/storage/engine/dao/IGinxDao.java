package com.storage.engine.dao;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.constant.IGinxConstants;
import org.springframework.stereotype.Repository;

import java.util.Locale;

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

  public void insertNode(long key, String name, String ip, String port, String description, boolean isValid) {
      String sql = String.format("insert into %s(key, name, ip, port, description, isValid) values (%d, '%s', '%s', '%s', '%s', %b);",
              IGinxConstants.NODES_PATH,
              key, name, ip, port, description, isValid);
      executeSql(sql);
  }

  public void updateNode(long key, String name, String ip, String port, String description) {
      insertNode(key, name, ip, port, description, true);
  }

  public void deleteNode(long key) {
      String sql = String.format("insert into %s(key, isValid) values (%d, false);",
              IGinxConstants.NODES_PATH, key);
      executeSql(sql);
  }

  public SessionExecuteSqlResult getAllNodes() {
      return executeSql("select * from " + IGinxConstants.NODES_PATH + ";");
  }

  public SessionExecuteSqlResult getNodeById(long key) {
      return executeSql("select * from " + IGinxConstants.NODES_PATH + " where key = " + key + ";");
  }

  public long getMaxNodeId() {
      SessionExecuteSqlResult result = executeSql("select last(name) from " + IGinxConstants.NODES_PATH + ";");
      if (result.getKeys() != null && result.getKeys().length > 0) {
          long[] keys = result.getKeys();
          return keys[keys.length - 1];
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

  private SessionExecuteSqlResult executeSql(String sql) {
    SessionExecuteSqlResult sqlResult;
    try {
      sqlResult = session.executeSql(sql);
    } catch (SessionException e) {
      throw new RuntimeException("Failed to execute SQL: " + sql, e);
    }
    return sqlResult;
  }
}
