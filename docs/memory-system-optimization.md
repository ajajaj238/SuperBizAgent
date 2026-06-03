# 记忆系统缺陷分析与优化方案

## 1. 当前记忆系统架构

```
浏览器 (localStorage)                 服务器 (内存)
┌─────────────────┐                ┌──────────────────────────────────┐
│  ChatHistories   │    sessionId   │  ConcurrentHashMap               │
│  (消息历史JSON)  │ ────────────→  │    ├─ session_xxx: SessionInfo   │
│                  │                │    │   messageHistory[]           │
│                  │                │    │   intentHistory[]            │
│                  │                │    │   createTime                 │
│                  │                │    └──────────────────────────────│
│                  │                │   MAX_WINDOW_SIZE=10              │
│                  │                │   (超限→LLM压缩摘要并丢弃原文)     │
│                  │                │                                   │
│                  │                │   服务器重启 = 全部丢失            │
└─────────────────┘                └──────────────────────────────────┘

          无用户概念                         无持久化
   所有 session 混在一起                关服务器即消失
```

---

## 2. 六大缺陷

### 缺陷一：纯内存存储，服务器重启一切归零

```java
// ChatController.java:67
private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
```

所有对话历史、意图流、记忆摘要都只存在这个 HashMap 里。重启/发版/崩溃——全部消失。

### 缺陷二：无用户隔离

当前仅靠前端生成的 `sessionId` 隔离，**没有用户概念**。多用户场景下：
- 两个用户各开一个页面，后台看到的只是两条孤立的 session
- 无法为不同用户提供个性化服务
- 管理员无法查看某个特定用户的历史会话
- 用户无法跨设备/跨浏览器延续对话

### 缺陷三：会话 ID 由前端生成，极易丢失

```javascript
// app.js:548
generateSessionId() {
    return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
}
```

- 页面刷新 → 新 ID → 原会话再也找不到
- 多标签页 → 各自生成 ID → 同一用户多个孤立会话

### 缺陷四：窗口压缩是不可逆的有损压缩

达到 10 轮对话后，原始消息被 LLM 或规则压缩成 2-3 句摘要，**原文永久删除**。用户回溯之前的话题时细节已丢失。

### 缺陷五：无跨会话长期记忆

用户 A 说"我是运维，关注 CPU 告警"，下一轮新会话时系统对此一无所知。每次对话都是"陌生人"。

### 缺陷六：无会话生命周期管理

- 建了 session 永不删除
- 无 TTL 过期机制
- 无最大会话数限制
- 长时间不用的一直占用内存

---

## 3. 优化后整体架构

### 3.1 数据流总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                        用户层 (支持多用户隔离)                         │
│  用户 A ── session_1, session_2, ...                                 │
│  用户 B ── session_3, session_4, ...                                 │
└──────────────────────────────────────────────────────────────────────┘

                                   │
                      ┌────────────▼────────────┐
                      │     SessionService       │
                      │    (统一会话管理)          │
                      └──────┬──────────────────┘
                             │
          ┌──────────────────┼──────────────────────┐
          ▼                  ▼                      ▼
┌─────────────────┐  ┌──────────────┐  ┌──────────────────────┐
│ L1 短期记忆      │  │ L2 强事实     │  │ L3 完整会话归档      │
│ Redis List       │  │ MySQL        │  │ JSON 文件            │
│ TTL=24h          │  │ 用户信息      │  │ data/sessions/{uid}/ │
│ 当前活跃对话上下文 │  │ 联系方式      │  │   {sessionId}/       │
│                   │  │ 配置偏好      │  │   messages.json      │
│                   │  │ 会话索引      │  │ 永久保留, 只追加      │
└─────────────────┘  └──────────────┘  └──────────────────────┘

                                          │
                                          │ 触发压缩（每N轮 / 关闭时）
                                          ▼
                                 ┌──────────────────────┐
                                 │ L4 语义记忆           │
                                 │ Milvus               │
                                 │ 对话摘要向量          │
                                 │ 用户兴趣偏好向量      │
                                 │ 仅存压缩后的语义      │
                                 │ 不存原始对话全文      │
                                 └──────────────────────┘
```

### 3.2 异构分层策略

| 层级 | 存储介质 | 存储内容 | TTL | 容量 | 用途 |
|------|---------|---------|-----|------|------|
| **L1** | Redis List | 最近 N 轮消息 + intent 流 | 24h | 每会话 ~50 条 | 当前对话的 LLM 上下文 |
| **L2** | MySQL | 用户信息、手机号、部门、配置偏好、会话索引 | 永久 | ~1k 用户 | 用户身份与结构化数据 |
| **L3** | JSON 文件 | 完整的消息历史（只追加） | 永久 | 无限制 | 审计回溯、UI 展示全文 |
| **L4** | Milvus | 对话语义摘要、用户兴趣偏好（LLM 压缩后） | 永久 | 无限制 | 跨会话的 LLM 上下文注入 |

### 3.3 核心原则

> **Redis 存最近 N 轮对话 → 直接喂给 LLM**
>
> **JSON 存完整历史 → 仅用于 UI 展示，不喂给 LLM**
>
> **Milvus 存压缩语义 → 会话关闭/到期时自动压缩写入，恢复时注入 LLM**

---

## 4. L1 短期记忆：Redis List + TTL

### 4.1 数据结构

```redis
# 会话消息列表（只保留最近 50 条，超出则截断左侧旧消息）
session:{sessionId}:messages → List<JSON>
  TTL = 86400 (24小时自动过期)

# 元素格式
{"role":"user", "content":"CPU高怎么处理", "timestamp":"...", "msgId":"m_001"}

# 用户→会话映射
user:{userId}:sessions → Set<sessionId>
```

### 4.2 服务实现

```java
@Service
public class RedisSessionStore {

    private static final int MAX_WINDOW = 50;  // Redis 最多保留 50 条

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 写入消息：同时写入 Redis 和 JSON
     */
    public void pushMessage(Long userId, String sessionId, ChatMessage msg) {
        // 1. Redis：右侧入队，超出截断
        String key = sessionKey(sessionId);
        redis.opsForList().rightPush(key, toJson(msg));
        redis.expire(key, 24, TimeUnit.HOURS);
        redis.opsForList().trim(key, -MAX_WINDOW, -1);  // 只保留最近 MAX_WINDOW 条

        // 2. JSON：异步追加到文件（L3 持久化）
        fileSessionStore.appendMessageAsync(userId, sessionId, msg);
    }

    /**
     * 读取最近 N 条（喂给 LLM 的上下文）
     */
    public List<ChatMessage> getRecentMessages(String sessionId, int n) {
        String key = sessionKey(sessionId);
        List<String> raw = redis.opsForList().range(key, -n, -1);
        if (raw != null && !raw.isEmpty()) return parse(raw);

        // Redis 未命中（过期或淘汰）→ 从 JSON 恢复最近 N 条 → 预热回 Redis
        return reloadFromJson(sessionId);
    }

    /**
     * 从 JSON 恢复最近 N 条并写回 Redis
     */
    private List<ChatMessage> reloadFromJson(String sessionId) {
        List<ChatMessage> all = fileSessionStore.readMessages(sessionId);
        if (all.isEmpty()) return List.of();

        List<ChatMessage> recent = all.size() > MAX_WINDOW
            ? all.subList(all.size() - MAX_WINDOW, all.size())
            : all;

        // 预热回 Redis
        String key = sessionKey(sessionId);
        redis.opsForList().rightPushAll(key, recent.stream().map(this::toJson).toList());
        redis.expire(key, 24, TimeUnit.HOURS);

        return recent;
    }
}
```

---

## 5. L2 强事实：MySQL

### 5.1 表结构

```sql
CREATE TABLE `user` (
  `id`           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `username`     VARCHAR(64)  NOT NULL UNIQUE,
  `display_name` VARCHAR(128),
  `department`   VARCHAR(128),
  `phone`        VARCHAR(32),
  `email`        VARCHAR(128),
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `user_preference` (
  `id`          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `user_id`     BIGINT       NOT NULL,
  `pref_key`    VARCHAR(64)  NOT NULL,
  `pref_value`  VARCHAR(256) NOT NULL,
  UNIQUE KEY `uk_user_pref` (`user_id`, `pref_key`)
);

CREATE TABLE `session_index` (
  `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL,
  `session_id`    VARCHAR(64)  NOT NULL,
  `title`         VARCHAR(256),             -- 自动生成：第一条消息的前30字
  `status`        TINYINT      DEFAULT 1,   -- 1:活跃, 0:已归档, -1:已删除
  `message_count` INT          DEFAULT 0,
  `summary`       TEXT,                     -- Milvus 语义摘要的冗余文本（快捷显示用）
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_session_id` (`session_id`)
);
```

### 5.2 用户隔离

```java
@Service
public class UserSessionService {

    /**
     * 创建新会话时绑定用户
     */
    public String createSession(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        sessionIndexMapper.insert(new SessionIndex(userId, sessionId));
        redis.opsForSet().add("user:%s:sessions".formatted(userId), sessionId);
        return sessionId;
    }

    /**
     * 获取用户的会话列表（供前端展示）
     */
    public List<SessionSummary> listSessions(Long userId) {
        return sessionIndexMapper.selectByUserId(userId);
    }

    /**
     * 校验会话归属
     */
    public boolean checkOwnership(Long userId, String sessionId) {
        return sessionIndexMapper.existsByUserIdAndSessionId(userId, sessionId);
    }
}
```

---

## 6. L3 完整会话归档：JSON 文件

### 6.1 存储结构

```
data/sessions/
└── {userId}/
    └── {sessionId}/
        ├── meta.json        # 会话元数据
        └── messages.json    # 完整消息历史（只追加）
```

**messages.json** — 每条消息实时追加，不删不改：

```json
[
  {"msgId":"m_001", "role":"user",      "content":"CPU高怎么处理",       "timestamp":"2026-06-02T19:46:36"},
  {"msgId":"m_002", "role":"assistant", "content":"根据SOP第3条...",     "timestamp":"2026-06-02T19:46:41"},
  {"msgId":"m_003", "role":"user",      "content":"帮我查一下当前CPU",     "timestamp":"2026-06-02T19:47:10"},
  {"msgId":"m_004", "role":"assistant", "content":"当前CPU 92%...",      "timestamp":"2026-06-02T19:47:15"}
]
```

### 6.2 写入策略

```java
@Service
public class FileSessionStore {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Queue<WriteTask> writeQueue = new ConcurrentLinkedQueue<>();

    /**
     * 异步追加写：不阻塞用户请求
     */
    public void appendMessageAsync(Long userId, String sessionId, ChatMessage msg) {
        writeQueue.add(new WriteTask(userId, sessionId, msg));
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 每 5 秒批量刷盘
     */
    private void flush() {
        // 按 sessionId 分组，批量写入
        Map<String, List<ChatMessage>> batch = new HashMap<>();
        WriteTask task;
        while ((task = writeQueue.poll()) != null) {
            batch.computeIfAbsent(task.key(), k -> new ArrayList<>()).add(task.msg);
        }
        batch.forEach((key, messages) -> appendToFile(key, messages));
    }

    /**
     * 读取完整消息列表（供 UI 展示，不喂给 LLM）
     */
    public List<ChatMessage> readAllMessages(Long userId, String sessionId) {
        Path file = path(userId, sessionId).resolve("messages.json");
        if (!Files.exists(file)) return List.of();
        return mapper.readValue(file.toFile(),
            new TypeReference<List<ChatMessage>>() {});
    }
}
```

### 6.3 关键原则

> **JSON 文件 = 完整审计日志，仅用于 UI 展示和历史回溯，永不直接喂给 LLM。**
>
> **喂给 LLM 的永远是：Redis 最近 N 条（热数据）+ Milvus 语义摘要（长期记忆）。**

---

## 7. L4 语义记忆：Milvus（压缩存储）

### 7.1 压缩触发时机

```
时机 A：实时压缩 ── 每累计 5 轮对话，自动触发一次摘要提取
时机 B：关闭压缩 ── 服务器 @PreDestroy 时，压缩所有活跃会话
时机 C：到期压缩 ── 定时任务扫描 23h 无活动的会话，压缩后释放 Redis
```

### 7.2 压缩内容

每条 Milvus 记录存储的是 **LLM 压缩后的语义摘要**，不是原始对话：

```json
// Milvus collection: user_memories
{
  "user_id":    1001,
  "session_id": "session_abc_123",
  "timestamp":  "2026-06-02T20:00:00",
  "insight":    "用户发现CPU告警(92%)，通过查询SOP中'CPU高使用率处理流程'执行了top命令排查，定位到是order-service的Java进程异常。已建议检查JVM内存配置。用户倾向立即处理而非等待。",
  "embedding":  [0.123, -0.456, ...]  // insight 的 text-embedding-v4 向量
}
```

### 7.3 压缩服务

```java
@Component
public class MemoryCompressionService {

    /**
     * 实时压缩：每 N 轮对话后触发
     */
    public void compressIfNeeded(Long userId, String sessionId, int messageCount) {
        if (messageCount % 5 != 0) return;  // 每 5 轮压缩一次

        List<ChatMessage> recentMessages = redisSessionStore.getRecentMessages(sessionId, 10);
        String summary = llmSummarize(recentMessages);
        storeToMilvus(userId, sessionId, summary);
    }

    /**
     * 关闭压缩：@PreDestroy 时触发
     */
    @PreDestroy
    public void compressAllOnShutdown() {
        List<ActiveSession> activeSessions = sessionManager.getAllActiveSessions();
        for (ActiveSession s : activeSessions) {
            try {
                String summary = llmSummarize(s.getMessages());
                storeToMilvus(s.getUserId(), s.getSessionId(), summary);
            } catch (Exception e) {
                log.warn("会话 {} 关闭压缩失败: {}", s.getSessionId(), e.getMessage());
            }
        }
    }

    /**
     * LLM 压缩：将原始对话压缩为结构化摘要
     */
    private String llmSummarize(List<ChatMessage> messages) {
        String prompt = """
            你是一个对话记忆压缩助手。将以下对话压缩为一段结构化摘要（150字内），要求：

            1. 用户的核心诉求和关注点
            2. 查询过的告警/指标/日志（含数值）
            3. 参考过的 SOP 或文档
            4. 已定位的原因和给出的建议
            5. 用户偏好的处理方式

            只输出摘要正文，不加标题。

            对话：
            %s
            """.formatted(formatMessages(messages));

        ChatResponse resp = chatModel.call(new Prompt(prompt));
        return resp.getResult().getOutput().getText();
    }
}
```

### 7.4 恢复时读取

```java
/**
 * 用户重新打开旧会话时，从 Milvus 读取压缩摘要注入 LLM
 */
public List<String> getMemoriesForContext(Long userId, String sessionId, String currentQuestion) {
    // 1. 搜索与当前问题相关的历史摘要
    float[] queryVec = embeddingService.embed(currentQuestion);
    List<SearchResult> memories = vectorSearchService.searchUserMemories(
        userId, queryVec, 3,
        // 过滤：同一 session + 跨 session 的都返回
        filterBySession(sessionId, includeCurrent = true)
    );

    // 2. 格式化为 LLM 上下文
    return memories.stream()
        .map(m -> "[历史上下文] " + m.getInsight())
        .toList();
}
```

---

## 8. 数据的完整生命周期

### 8.1 实时写入路径

```
用户发送消息
    │
    ├─→ Redis List (L1)
    │      rightPush, trim(最近50条), EXPIRE 24h
    │      ↑ 给 LLM 的短期上下文
    │
    ├─→ 异步写入 JSON 文件 (L3)
    │      积攒 5 秒 → 批量刷盘
    │      ↑ 完整审计日志，UI 历史展示
    │
    └─→ 每 5 轮触发 Milvus 压缩 (L4)
           LLM 提取摘要 → 向量化 → 存入 Milvus
           ↑ 跨会话长期语义记忆
```

### 8.2 用户重新打开旧会话

```
用户点击会话 "CPU 高告警排查"
    │
    ├─ 校验归属 (MySQL session_index)
    │
    ├─ 读 Redis (L1)
    │     KEY: session:xxx:messages
    │     │
    │     ├─ 命中 (24h内活跃过)
    │     │     └─ 取出最近 N 条 → 喂给 LLM
    │     │
    │     └─ 未命中
    │           └─ 从 JSON (L3) 恢复最近 50 条 → 预热回 Redis → 喂给 LLM
    │
    ├─ 读 Milvus (L4)
    │     搜索该 session + 该用户的跨会话记忆
    │     将压缩摘要注入 system prompt
    │
    ├─ 组装 LLM 上下文
    │     System Prompt = 角色定义 + Milvus 记忆摘要
    │     User Message = Redis 最近 N 条 + 当前问题
    │
    └─ 返回前端
          消息列表（来自 Redis 或 JSON）
          + LLM 回答（基于 Redis + Milvus 上下文）
```

### 8.3 LLM 上下文构成

```
┌──────────────────────────────────────────────┐
│ System Prompt                                 │
│   - 角色定义                                  │
│   - [来自 Milvus] 你之前的关注点是 CPU 告警，  │  ← 压缩摘要，不超 200 字
│     当时定位到 order-service Java 进程异常...  │
├──────────────────────────────────────────────┤
│ User Message                                  │
│   - [来自 Redis] 最近 3 轮对话                │  ← 最近上下文，不超 10 条
│   - 当前用户输入                              │
└──────────────────────────────────────────────┘

不包含：完整 JSON 历史 ❌
```

### 8.4 服务器关闭流程

```
@PreDestroy
    │
    ├─ 遍历所有活跃会话
    │     │
    │     ├─ 从 Redis 读取最近消息
    │     ├─ LLM 压缩为摘要
    │     ├─ 向量化存入 Milvus (L4)
    │     └─ 更新 MySQL session_index.summary
    │
    ├─ Redis 数据自然丢失（重启后 Redis 空）
    │   (不主动清理，让 TTL 自然过期)
    │
    └─ JSON 文件 (L3) 已在运行中实时写入，无需额外操作
```

**关闭后恢复时**：
- Redis 空了 → 从 JSON 恢复最近 50 条
- Milvus 有摘要 → 注入 LLM 上下文
- 用户看到完整的历史 UI（来自 JSON）

---

## 9. 各场景上下文对比

| 场景 | Redis | 喂给 LLM 的内容 | Milvus |
|------|-------|---------------|--------|
| 当前活跃会话 | 有（最近 50 条） | **Redis 最近 10 条 + Milvus 摘要** | 最近 5 轮已压缩 |
| Redis 过期，重新打开 | 从 JSON 恢复 | **JSON 恢复的最近 10 条 + Milvus 摘要** | 完整会话摘要 |
| 6 个月前的会话 | 没有 | **仅 Milvus 摘要**（无原始消息上下文） | 有（不因时间丢失） |
| 其他用户的相关记忆 | 没有 | **仅 Milvus 跨用户摘要** | 如果有语义关联 |
| 用户查看完整历史 | UI 展示 JSON 全文 | **不喂给 LLM** | 不涉及 |

---

## 10. 新增依赖

```xml
<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

---

## 11. 配置项

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 16
        max-idle: 8
        min-idle: 4

  datasource:
    url: jdbc:mysql://localhost:3306/superbiz?useUnicode=true&characterEncoding=utf8mb4
    username: root
    password: ${MYSQL_PASSWORD}

session:
  storage:
    path: ./data/sessions           # JSON 文件根目录
    redis-ttl: 24h                  # Redis 过期时间
    redis-max-messages: 50          # Redis 最多保留消息数
    compression-interval: 5         # 每 5 轮触发一次 Milvus 压缩
    llm-context-window: 10          # 喂给 LLM 的最近消息数
```

---

## 12. 新旧对比

| 维度 | 当前 | 优化后 |
|------|------|--------|
| 用户隔离 | 无 | MySQL user + session_index |
| 短期记忆 | HashMap，重启丢 | Redis List，TTL=24h，重启后从 JSON 恢复 |
| 喂给 LLM 的内容 | 全部历史 | 最近 10 条 + Milvus 压缩摘要 |
| 完整会话 | 10轮后压缩丢弃 | JSON 文件永久保留（仅 UI 展示） |
| 跨会话长期记忆 | 无 | Milvus 向量化语义摘要 |
| 服务器重启 | 全部丢失 | 从 JSON + Milvus 恢复 |
| 发版影响 | 用户断线失忆 | 会话列表、历史、记忆完整保留 |

---

## 13. 实现路线图

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| **Phase 1** | MySQL 用户表 + session_index + 用户隔离 | 2天 |
| **Phase 2** | Redis List + TTL + JSON 异步写入 | 2天 |
| **Phase 3** | Milvus 压缩（实时 + 关闭时）+ 恢复流程 | 3天 |
| **Phase 4** | 前端改造（会话列表、历史加载、持久化 sessionId） | 2天 |

### 建议执行顺序

```
Phase 1 (用户隔离) → Phase 2 (Redis+JSON) → Phase 3 (Milvus压缩) → Phase 4 (前端)
```
