package org.example.config;

/**
 * 请求级用户上下文
 * 通过 JwtAuthFilter 在每个请求开始时注入，请求结束后清除
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void set(Long userId, String username, String role) {
        CONTEXT.set(new UserInfo(userId, username, role));
    }

    public static Long getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.userId() : null;
    }

    public static String getUsername() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.username() : null;
    }

    public static String getRole() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.role() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record UserInfo(Long userId, String username, String role) {}
}
