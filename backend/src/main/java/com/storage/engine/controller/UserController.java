package com.storage.engine.controller;

import com.storage.engine.model.Response;
import com.storage.engine.model.User;
import com.storage.engine.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/config/users")
    public ResponseEntity<Response<List<User>>> getAllUsers() {
        return ResponseEntity.ok(Response.success(userService.getAllUsers()));
    }

    @PostMapping("/config/users")
    public ResponseEntity<Response<User>> createUser(@ModelAttribute User user) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(userService.createUser(user)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.error(400, e.getMessage()));
        }
    }

    @GetMapping("/config/users/{id}")
    public ResponseEntity<Response<User>> getUserById(@PathVariable Integer id) {
        User user = userService.getUserById(id);
        if (user != null) {
            return ResponseEntity.ok(Response.success(user));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "User not found"));
        }
    }

    @PutMapping("/config/users/{id}")
    public ResponseEntity<Response<User>> updateUser(@PathVariable Integer id, @ModelAttribute User user) {
        User updatedUser = userService.updateUser(id, user);
        if (updatedUser != null) {
            return ResponseEntity.ok(Response.success(updatedUser));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "User not found"));
        }
    }

    @DeleteMapping("/config/users/{id}")
    public ResponseEntity<Response<Void>> deleteUser(@PathVariable Integer id) {
        boolean deleted = userService.deleteUser(id);
        if (deleted) {
            return ResponseEntity.ok(Response.success());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(404, "User not found"));
        }
    }

    @PostMapping("/config/login")
    public ResponseEntity<Response<User>> login(@RequestParam String username, @RequestParam String password) {
        User user = userService.login(username, password);
        if (user != null) {
            return ResponseEntity.ok(Response.success(user));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Response.error(401, "Invalid username or password"));
        }
    }
}
