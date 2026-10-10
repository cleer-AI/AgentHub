package org.example.controller;

import org.example.entity.User;
import org.example.service.UserService;
import org.example.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody User user) {
        Map<String, Object> response = new HashMap<>();
        try {
            User newUser = userService.register(user);
            response.put("success", true);
            response.put("message", "注册成功");
            response.put("data", new UserResponse(newUser.getId(), newUser.getUsername(), newUser.getEmail(), newUser.getPhone()));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = userService.login(request.get("username"), request.get("password"));

            String token = jwtUtil.generateToken(user.getId(), user.getUsername());

            response.put("success", true);
            response.put("message", "登录成功");
            response.put("token", token);
            response.put("user", new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getPhone()));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private record UserResponse(Long id, String username, String email, String phone) {}

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getUserInfo(@RequestHeader("Authorization") String token) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }

            if (jwtUtil.validateToken(token)) {
                Long userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);

                response.put("success", true);
                response.put("userId", userId);
                response.put("username", username);
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "无效的 token");
                return ResponseEntity.status(401).body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "token 解析失败");
            return ResponseEntity.status(401).body(response);
        }
    }
}
