package org.example.config;

import org.example.entity.UserAccount;
import org.example.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时初始化种子用户
 * 如果 admin 用户不存在，则创建默认用户
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userAccountMapper.findUser() > 0) {
            logger.info("种子用户已存在，跳过初始化");
            return;
        }

        List<UserAccount> seedUsers = List.of(
                createUser("admin", "admin123", "系统管理员", "admin"),
                createUser("zhangsan", "admin123", "张三", "user"),
                createUser("lisi", "admin123", "李四", "user")
        );

        for (UserAccount user : seedUsers) {
            userAccountMapper.insert(user);
            logger.info("创建种子用户: {} ({})", user.getUsername(), user.getDisplayName());
        }

        logger.info("种子用户初始化完成，共 {} 人", seedUsers.size());
    }

    private UserAccount createUser(String username, String password, String displayName, String role) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }
}
