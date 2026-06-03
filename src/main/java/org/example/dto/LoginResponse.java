package org.example.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private UserInfo user;

    public LoginResponse(String token, UserInfo user) {
        this.token = token;
        this.user = user;
    }

    @Data
    public static class UserInfo {
        private Long id;
        private String username;
        private String displayName;
        private String role;

        public UserInfo(Long id, String username, String displayName, String role) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.role = role;
        }
    }
}
