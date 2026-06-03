package org.example.controller;

import org.example.config.JwtService;
import org.example.dto.LoginRequest;
import org.example.dto.LoginResponse;
import org.example.entity.UserAccount;
import org.example.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /**
     * 用户登录
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "用户名不能为空"));
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "密码不能为空"));
        }

        var userOpt = authService.authenticate(request.getUsername(), request.getPassword());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "用户名或密码错误"));
        }

        UserAccount user = userOpt.get();
        String token = jwtService.generateToken(user);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );

        LoginResponse loginResponse = new LoginResponse(token, userInfo);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", loginResponse
        ));
    }

    /**
     * 获取当前登录用户信息
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        var userOpt = authService.getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未登录"));
        }

        UserAccount user = userOpt.get();
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "displayName", user.getDisplayName(),
                        "role", user.getRole()
                )
        ));
    }
}
