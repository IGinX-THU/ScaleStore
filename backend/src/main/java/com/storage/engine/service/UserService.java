package com.storage.engine.service;

import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import com.storage.engine.dao.IGinxDao;
import com.storage.engine.model.User;
import com.storage.engine.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private IGinxDao iginxDao;

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        try {
            SessionExecuteSqlResult result = iginxDao.getAllUsers();
            if (result != null) {
               users = parseUsers(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return users;
    }

    public synchronized User createUser(User user) {
        List<User> allUsers = getAllUsers();
        for (User u : allUsers) {
            if (u.getUsername().equals(user.getUsername())) {
                throw new RuntimeException("Username already exists");
            }
        }

        if (user.getId() == null) {
            long maxId = iginxDao.getMaxId();
            user.setId((int) (maxId + 1));
        }

        if (user.getPassword() != null) {
            user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        }
        
        user.setIsValid(true);
        iginxDao.insertUser(user.getId(), user.getUsername(), user.getPassword(), user.getType(), user.getEmail(), user.getPhone(), true);
        return user;
    }

    public User getUserById(Integer id) {
        try {
            SessionExecuteSqlResult result = iginxDao.getUserById(id);
            List<User> users = parseUsers(result);
            if (!users.isEmpty()) {
                return users.get(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public User updateUser(Integer id, User user) {
        User existing = getUserById(id);
        if (existing != null) {
             user.setId(id);
             // If password is updated, encrypt it
             if (user.getPassword() != null && !user.getPassword().isEmpty()) {
                 user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
             } else {
                 // Keep old password if new one is empty
                 user.setPassword(existing.getPassword());
             }
             iginxDao.updateUser(id, user.getUsername(), user.getPassword(), user.getType(), user.getEmail(), user.getPhone());
             user.setIsValid(true);
             return user;
        }
        return null;
    }

    public boolean deleteUser(Integer id) {
        User existing = getUserById(id);
        if (existing != null) {
            iginxDao.deleteUser(id);
            return true;
        }
        return false;
    }

    public User login(String username, String password) {
        if (username == null || password == null) return null;
        
        List<User> allUsers = getAllUsers();
        String encryptedPassword = SecurityUtils.encryptPassword(password);
        
        for (User user : allUsers) {
            if (user.getUsername().equals(username) && 
                user.getPassword() != null && 
                user.getPassword().equals(encryptedPassword)) {
                return user;
            }
        }
        return null;
    }

    private List<User> parseUsers(SessionExecuteSqlResult result) {
        List<User> users = new ArrayList<>();
        long[] keys = result.getKeys();
        List<List<Object>> values = result.getValues();
        List<String> paths = result.getPaths();

        int usernameIdx = -1, passwordIdx = -1, typeIdx = -1, emailIdx = -1, phoneIdx = -1, isValidIdx = -1;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (path.endsWith("username")) usernameIdx = i;
            else if (path.endsWith("password")) passwordIdx = i;
            else if (path.endsWith("type")) typeIdx = i;
            else if (path.endsWith("email")) emailIdx = i;
            else if (path.endsWith("phone")) phoneIdx = i;
            else if (path.endsWith("isValid")) isValidIdx = i;
        }

        for (int i = 0; i < keys.length; i++) {
            User user = new User();
            user.setId((int)keys[i]);
            List<Object> row = values.get(i);

            if (usernameIdx != -1) user.setUsername(getValueAsString(row.get(usernameIdx)));
            if (passwordIdx != -1) user.setPassword(getValueAsString(row.get(passwordIdx)));
            if (typeIdx != -1) user.setType(getValueAsInt(row.get(typeIdx)));
            if (emailIdx != -1) user.setEmail(getValueAsString(row.get(emailIdx)));
            if (phoneIdx != -1) user.setPhone(getValueAsString(row.get(phoneIdx)));

            boolean isValid = true;
            if (isValidIdx != -1) {
                 isValid = getValueAsBoolean(row.get(isValidIdx));
            }
            user.setIsValid(isValid);
            if (isValid) {
                users.add(user);
            }
        }
        return users;
    }

    private String getValueAsString(Object obj) {
        return obj == null ? null : new String((byte[])obj); // IGinX often returns bytes for strings
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

    private Boolean getValueAsBoolean(Object obj) {
       if (obj == null) return false;
       if (obj instanceof Boolean) return (Boolean)obj;
       if (obj instanceof byte[]) {
           return Boolean.parseBoolean(new String((byte[])obj));
       }
       return false;
    }
}
