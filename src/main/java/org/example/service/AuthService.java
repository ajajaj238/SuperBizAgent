package org.example.service;

import org.example.config.UserContext;
import org.example.entity.UserAccount;
import org.example.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 认证用户：校验用户名密码
     * @return 认证成功返回 UserAccount，失败返回 empty
     */
    public Optional<UserAccount> authenticate(String username, String password) {
        Optional<UserAccount> userOpt = userAccountMapper.findByUsername(username);
        if (userOpt.isEmpty()) {
            logger.warn("登录失败：用户 {} 不存在", username);
            return Optional.empty();
        }

        UserAccount user = userOpt.get();
        if (user.getStatus() == null || user.getStatus() != 1) {
            logger.warn("登录失败：用户 {} 已停用", username);
            return Optional.empty();
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            logger.warn("登录失败：用户 {} 密码错误", username);
            return Optional.empty();
        }

        // 更新最后登录时间
        userAccountMapper.updateLastLogin(user.getId());

        logger.info("用户 {} 登录成功", username);
        return Optional.of(user);
    }

    /**
     * 获取当前登录用户信息
     */
    public Optional<UserAccount> getCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) return Optional.empty();
        return userAccountMapper.findById(userId);
    }
}
