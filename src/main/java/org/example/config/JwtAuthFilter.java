package org.example.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器
 * 拦截所有 /api/* 请求，验证 JWT token，并将用户信息注入 UserContext
 */
@Component
@Order(1)
public class JwtAuthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login"
    );

    private static final List<String> STATIC_RESOURCES = List.of(
            "/login.html", "/login.js", "/login.css",
            "/index.html", "/app.js", "/styles.css",
            "/favicon.ico"
    );

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 放行 OPTIONS 预检请求
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 放行静态资源和白名单
        if (isWhiteListed(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 只拦截 /api/ 开头的请求
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 验证 token
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(resp, "未登录");
            return;
        }

        try {
            String token = authHeader.substring(7);
            var claims = jwtService.parseToken(token);
            UserContext.set(
                    claims.get("user_id", Long.class),
                    claims.getSubject(),
                    claims.get("role", String.class)
            );
        } catch (Exception e) {
            logger.warn("JWT 验证失败: {}", e.getMessage());
            unauthorized(resp, "令牌无效或已过期");
            UserContext.clear();
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private boolean isWhiteListed(String path) {
        if (WHITE_LIST.contains(path)) return true;
        for (String resource : STATIC_RESOURCES) {
            if (path.equals(resource)) return true;
        }
        return false;
    }

    private void unauthorized(HttpServletResponse resp, String msg) throws IOException {
        if (resp.isCommitted()) {
            logger.warn("响应已提交，跳过401写入: {}", msg);
            return;
        }
        resp.resetBuffer();
        resp.setStatus(401);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getOutputStream().write(("{\"code\":401,\"message\":\"" + msg + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
