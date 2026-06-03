# 用户隔离 + 前端登录方案

## 1. 背景

当前项目**无用户概念**，仅靠前端随机生成的 sessionId 做隔离。需要：

- **用户认证**：用户登录后才能使用
- **用户隔离**：每个用户只能看到自己的会话和数据
- **前端登录页**：未登录用户重定向到登录页

---

## 2. 整体架构

```
┌────────────────────────────────────────────────────┐
│                   用户层                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ 张三     │  │ 李四     │  │ 王五     │  ← 用户  │
│  │ session1 │  │ session3 │  │ session5 │         │
│  │ session2 │  │ session4 │  │          │         │
│  └─────┬────┘  └─────┬────┘  └────┬─────┘         │
│        │             │             │               │
│        └─────────────┼─────────────┘               │
│                      │                             │
│                 ┌────▼────┐                        │
│                 │ JWT     │                        │
│                 │ Filter  │  ← 提取 user_id        │
│                 └────┬────┘                        │
│                      │                             │
│                 ┌────▼────────────────┐            │
│                 │ 业务层 (按 user_id 隔离) │         │
│                 │ session_index / JSON │           │
│                 │ Redis / Milvus      │            │
│                 └─────────────────────┘            │
└────────────────────────────────────────────────────┘
```

---

## 3. 数据库设计

### 3.1 用户表

```sql
CREATE TABLE `user` (
  `id`           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `username`     VARCHAR(64)  NOT NULL UNIQUE,   -- 登录账号
  `password`     VARCHAR(256) NOT NULL,           -- bcrypt 加密
  `display_name` VARCHAR(128),
  `role`         VARCHAR(32)  DEFAULT 'user',     -- admin / user
  `phone`        VARCHAR(32),
  `email`        VARCHAR(128),
  `status`       TINYINT      DEFAULT 1,          -- 1:启用, 0:停用
  `last_login`   DATETIME,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 种子数据（密码均为 admin123，bcrypt 加密）
-- INSERT INTO user (username, password, display_name, role) VALUES ('admin', '$2a$10$...', '管理员', 'admin');
-- INSERT INTO user (username, password, display_name, role) VALUES ('zhangsan', '$2a$10$...', '张三', 'user');
-- INSERT INTO user (username, password, display_name, role) VALUES ('lisi', '$2a$10$...', '李四', 'user');
```

### 3.2 会话索引表

```sql
CREATE TABLE `session_index` (
  `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL,
  `session_id`    VARCHAR(64)  NOT NULL,
  `title`         VARCHAR(256),              -- 第一条消息的前30字
  `status`        TINYINT      DEFAULT 1,    -- 1:活跃, 0:已归档, -1:已删除
  `message_count` INT          DEFAULT 0,
  `summary`       TEXT,                      -- Milvus 摘要的快照文本
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_session_id` (`session_id`)
);
```

### 3.3 数据隔离方式

| 数据 | 隔离方式 | 示例 |
|------|---------|------|
| MySQL | 所有查询带 `WHERE user_id = ?` | `WHERE user_id = 1001` |
| Redis | Key 嵌入 user_id | `user:1001:session:xxx:messages` |
| JSON 文件 | 目录按 user_id 分割 | `data/sessions/{userId}/{sessionId}/` |
| Milvus | 字段中存 user_id，搜索时加 filter | `filter = "user_id == 1001"` |

---

## 4. 认证流程

### 4.1 登录 API

```
POST /api/auth/login
Content-Type: application/json

Request:
{
  "username": "zhangsan",
  "password": "admin123"
}

Response 200:
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOi...",
    "expiresIn": 86400,
    "user": {
      "id": 1001,
      "username": "zhangsan",
      "displayName": "张三",
      "role": "user"
    }
  }
}

Response 401:
{
  "code": 401,
  "message": "用户名或密码错误"
}
```

### 4.2 JWT 结构

```json
{
  "sub": "zhangsan",
  "user_id": 1001,
  "role": "user",
  "exp": 1717449600,
  "iat": 1717363200
}
```

### 4.3 控制器

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest req) {
        // 1. 查找用户
        User user = userService.findByUsername(req.getUsername());
        if (user == null || user.getStatus() != 1) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("用户名或密码错误"));
        }

        // 2. 校验密码 (bcrypt)
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("用户名或密码错误"));
        }

        // 3. 生成 JWT，24h 过期
        String token = jwtService.generateToken(user);

        return ResponseEntity.ok(ApiResponse.success(new LoginResponse(token, user)));
    }
}
```

### 4.4 JWT 过滤器

```java
@Component
public class JwtAuthFilter implements Filter {

    // 白名单：不需要认证的路径
    private static final List<String> WHITE_LIST = List.of(
        "/api/auth/login",
        "/login.html",
        "/login.js",
        "/login.css"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // 白名单放行
        if (isWhiteListed(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 所有 /api/* 和页面资源需要认证
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "未登录");
            return;
        }

        try {
            Claims claims = jwtService.parseToken(authHeader.substring(7));
            // 注入当前用户上下文
            UserContext.set(
                claims.get("user_id", Long.class),
                claims.getSubject()
            );
            chain.doFilter(request, response);
        } catch (Exception e) {
            unauthorized(response, "令牌无效或已过期");
        } finally {
            UserContext.clear();
        }
    }
}
```

### 4.5 UserContext（线程级上下文）

```java
public class UserContext {
    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void set(Long userId, String username) {
        CONTEXT.set(new UserInfo(userId, username));
    }

    public static Long getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.userId() : null;
    }

    public static String getUsername() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.username() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record UserInfo(Long userId, String username) {}
}
```

### 4.6 Service 中的数据隔离

所有 Service 中通过 `UserContext.getUserId()` 获取当前用户，自动限定数据范围：

```java
@Service
public class SessionService {

    public List<SessionSummary> listSessions() {
        Long userId = UserContext.getUserId();
        return sessionIndexMapper.selectByUserId(userId);
    }

    public void createSession(String sessionId) {
        Long userId = UserContext.getUserId();
        sessionIndexMapper.insert(new SessionIndex(userId, sessionId));
        // Redis Key 带 user_id
        redis.opsForSet().add("user:%s:sessions".formatted(userId), sessionId);
    }

    public boolean checkOwnership(String sessionId) {
        Long userId = UserContext.getUserId();
        return sessionIndexMapper.existsByUserIdAndSessionId(userId, sessionId);
    }
}
```

---

## 5. 前端登录页面

### 5.1 文件结构

```
static/
├── login.html       ← 新增：登录页
├── login.js         ← 新增：登录逻辑
├── login.css        ← 新增：登录页样式
├── index.html       ← 改造：需要登录才能访问
├── app.js           ← 改造：添加认证逻辑（apiFetch 封装）
└── styles.css       ← 改造：保持不变
```

### 5.2 login.html

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>登录 - 智能OnCall助手</title>
    <link rel="stylesheet" href="login.css">
</head>
<body>
    <div class="login-container">
        <div class="login-card">
            <!-- Logo + 标题 -->
            <div class="login-header">
                <div class="login-logo">
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
                        <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"
                              fill="#4A9EFF"/>
                    </svg>
                </div>
                <h1>智能 OnCall 助手</h1>
                <p class="login-subtitle">AI 驱动的智能运维平台</p>
            </div>

            <!-- 登录表单 -->
            <form class="login-form" id="loginForm">
                <div class="form-group">
                    <label>用户名</label>
                    <input type="text" id="usernameInput"
                           placeholder="请输入用户名"
                           autocomplete="username"
                           required>
                </div>
                <div class="form-group">
                    <label>密码</label>
                    <input type="password" id="passwordInput"
                           placeholder="请输入密码"
                           autocomplete="current-password"
                           required>
                </div>
                <button type="submit" class="login-btn" id="loginBtn">
                    <span class="btn-text">登 录</span>
                    <span class="btn-loading" style="display:none">
                        <svg class="spin" width="18" height="18" viewBox="0 0 24 24" fill="none">
                            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" opacity="0.3"/>
                            <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-width="3" stroke-linecap="round"/>
                        </svg>
                        登录中...
                    </span>
                </button>
                <div class="login-error" id="loginError" style="display:none"></div>
            </form>

            <!-- 记忆上次登录的用户名 -->
            <div class="login-options">
                <label class="checkbox-label">
                    <input type="checkbox" id="rememberMe" checked>
                    <span>记住用户名</span>
                </label>
            </div>

            <div class="login-footer">
                <p>首次使用请联系管理员开通账号</p>
            </div>
        </div>
    </div>
    <script src="login.js"></script>
</body>
</html>
```

### 5.3 login.js

```javascript
document.addEventListener('DOMContentLoaded', () => new LoginApp());

class LoginApp {

    constructor() {
        this.form = document.getElementById('loginForm');
        this.usernameInput = document.getElementById('usernameInput');
        this.passwordInput = document.getElementById('passwordInput');
        this.loginBtn = document.getElementById('loginBtn');
        this.errorEl = document.getElementById('loginError');
        this.rememberMe = document.getElementById('rememberMe');

        // 已登录则直接跳转
        if (localStorage.getItem('token')) {
            window.location.href = '/index.html';
            return;
        }

        this.restoreUsername();
        this.form.addEventListener('submit', (e) => {
            e.preventDefault();
            this.login();
        });
    }

    restoreUsername() {
        const saved = localStorage.getItem('savedUsername');
        if (saved) {
            this.usernameInput.value = saved;
            this.passwordInput.focus();
        }
    }

    async login() {
        const username = this.usernameInput.value.trim();
        const password = this.passwordInput.value;

        if (!username || !password) {
            this.showError('请输入用户名和密码');
            return;
        }

        this.setLoading(true);
        this.hideError();

        try {
            const resp = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            const data = await resp.json();

            if (data.code === 200 && data.data?.token) {
                // 保存登录态
                localStorage.setItem('token', data.data.token);
                localStorage.setItem('user', JSON.stringify(data.data.user));
                if (this.rememberMe.checked) {
                    localStorage.setItem('savedUsername', username);
                } else {
                    localStorage.removeItem('savedUsername');
                }
                window.location.href = '/index.html';
            } else {
                this.showError(data.message || '登录失败');
            }
        } catch (err) {
            this.showError('网络错误，请稍后重试');
        } finally {
            this.setLoading(false);
        }
    }

    setLoading(loading) {
        this.loginBtn.disabled = loading;
        document.querySelector('.btn-text').style.display = loading ? 'none' : '';
        document.querySelector('.btn-loading').style.display = loading ? '' : 'none';
    }

    showError(msg) {
        this.errorEl.textContent = msg;
        this.errorEl.style.display = '';
    }

    hideError() {
        this.errorEl.style.display = 'none';
    }
}
```

### 5.4 app.js 改造

```javascript
class SuperBizAgentApp {

    constructor() {
        // 检查登录态
        const token = localStorage.getItem('token');
        if (!token) {
            window.location.href = '/login.html';
            return;
        }
        this.token = token;
        this.currentUser = JSON.parse(localStorage.getItem('user') || '{}');

        // 原有初始化...
        this.apiBaseUrl = '/api';
        this.sessionId = this.loadSessionId() || this.generateSessionId();
        this.initializeElements();
        this.bindEvents();
        // ...
    }

    /**
     * 统一的 API 请求封装
     */
    async apiFetch(url, options = {}) {
        const resp = await fetch(url, {
            ...options,
            headers: {
                'Authorization': 'Bearer ' + this.token,
                'Content-Type': 'application/json',
                ...options.headers
            }
        });

        if (resp.status === 401) {
            // Token 过期，跳回登录页
            localStorage.clear();
            window.location.href = '/login.html';
            return null;
        }

        return resp;
    }

    /**
     * 退出登录
     */
    logout() {
        localStorage.clear();
        window.location.href = '/login.html';
    }

    // 所有原有的 fetch 调用改为 this.apiFetch()
    async sendQuickMessage(message) {
        const resp = await this.apiFetch(`${this.apiBaseUrl}/chat`, {
            method: 'POST',
            body: JSON.stringify({ Id: this.sessionId, Question: message })
        });
        if (!resp) return;
        // ... 原有逻辑
    }
}
```

### 5.5 login.css 要点

```css
/* 全屏居中 + 深色渐变背景（匹配运维系统风格） */
.login-container {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, #0f1923 0%, #1a2a3a 50%, #0d1b2a 100%);
}

/* 玻璃态卡片 */
.login-card {
    background: rgba(255, 255, 255, 0.05);
    backdrop-filter: blur(20px);
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 16px;
    padding: 48px 40px;
    width: 400px;
    box-shadow: 0 25px 50px rgba(0, 0, 0, 0.5);
}

/* 科技蓝按钮 */
.login-btn {
    width: 100%;
    padding: 12px;
    background: linear-gradient(135deg, #4A9EFF, #2B7DE9);
    border: none;
    border-radius: 8px;
    color: white;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.3s;
}
.login-btn:hover { transform: translateY(-1px); box-shadow: 0 8px 24px rgba(74, 158, 255, 0.3); }

/* 输入框深色风格 */
.form-group input {
    width: 100%;
    padding: 12px 16px;
    background: rgba(255, 255, 255, 0.06);
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 8px;
    color: #e0e0e0;
    font-size: 14px;
    transition: border-color 0.2s;
}
.form-group input:focus {
    outline: none;
    border-color: #4A9EFF;
    box-shadow: 0 0 0 3px rgba(74, 158, 255, 0.15);
}
```

---

## 6. 新增依赖

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>

<!-- 密码加密（仅依赖 crypto，不需完整 Spring Security） -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

---

## 7. 新增文件清单

| 文件 | 作用 |
|------|------|
| `controller/AuthController.java` | 登录 API |
| `service/AuthService.java` | 认证业务逻辑 |
| `service/UserService.java` | 用户 CRUD |
| `config/JwtAuthFilter.java` | JWT 过滤器 |
| `config/JwtService.java` | JWT 生成与解析 |
| `config/UserContext.java` | 线程级用户上下文 |
| `config/WebConfig.java` | 注册 Filter（已有，需改造） |

| 前端 | |
|------|------|
| `static/login.html` | 登录页面 |
| `static/login.js` | 登录逻辑 |
| `static/login.css` | 登录页样式 |

---

## 8. 实现路线图

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| **Phase 1** | MySQL user 表 + JWT 工具 + AuthController | 1.5天 |
| **Phase 2** | JwtAuthFilter + UserContext + Service 数据隔离改造 | 1.5天 |
| **Phase 3** | 前端登录页 + app.js 认证封装 | 1天 |
| **Phase 4** | 已有 session 数据迁移 + 边界 case（token 刷新、记住密码） | 1天 |

### 建议执行顺序

```
Phase 1 (后端认证) → Phase 3 (前端登录) → Phase 2 (数据隔离) → Phase 4 (验证)
```

最快路径：Phase 1 + 3 = 2.5 天即可跑通"登录→进主页面→API 带 token"，数据隔离可以逐步改造。

---

## 9. 效果对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 登录 | 直接进系统 | 登录页 → JWT 认证 |
| 用户隔离 | 无 | 每个用户只看自己的会话 |
| 数据安全 | 任何人知道 URL 就能用 | 需要账号密码 |
| 会话列表 | 仅前端 localStorage | 服务端持久化，跨设备可用 |
| 退出登录 | 无 | 清除 token，跳回登录页 |
