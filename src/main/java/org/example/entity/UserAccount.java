package org.example.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserAccount {
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private String department;
    private String phone;
    private String email;
    private Integer status;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
