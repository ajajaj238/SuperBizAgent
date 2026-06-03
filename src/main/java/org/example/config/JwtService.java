package org.example.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.entity.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration}") long expiration) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 为用户生成 JWT token
     */
    public String generateToken(UserAccount user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("user_id", user.getId())
                .claim("role", user.getRole())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration * 1000))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 JWT token，返回 Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 中提取用户 ID
     */
    public Long getUserId(String token) {
        return parseToken(token).get("user_id", Long.class);
    }

    /**
     * 从 token 中提取用户名
     */
    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }
}
