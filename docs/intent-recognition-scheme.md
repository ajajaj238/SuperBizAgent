# 用户意图识别方案

## 1. 背景与目标

### 1.1 现状分析

当前 SuperBizAgent 的用户消息处理流程存在以下问题：

- **单一路由**：所有用户请求（除 AIOps 按钮外）都进入同一个 ReactAgent，由 LLM 自行判断调用哪些工具
- **系统提示词膨胀**：`ChatService.buildSystemPrompt()` 中堆砌了大量工具选择规则（见 [ChatService.java:136-147](../src/main/java/org/example/service/ChatService.java)），但仍无法完全避免工具误调用
- **AIOps 入口孤立**：智能运维功能通过前端独立按钮触发（`/api/ai_ops`），用户无法通过自然语言启动告警分析流程
- **缺乏结构化路由**：没有预处理层来识别用户意图，所有语义理解都压给 ReactAgent 的 LLM 调用

### 1.2 目标

引入用户意图识别层，实现：

1. **精确路由**：根据用户输入意图，将请求路由到最合适的处理链
2. **降本增效**：避免不必要的工具调用，减少 LLM 反复推理的 Token 消耗
3. **体验统一**：用户可通过自然语言触发所有功能，包括 AIOps
4. **可观测性**：记录用户意图分布，为产品迭代提供数据支撑

---

## 2. 整体架构

### 2.1 架构总览

```
用户输入 ──→ 文本向量化 (text-embedding-v4) ──→ Milvus 意图示例库搜索 ──→ 意图分类
                                                            │
                                        置信度 ≥ τ (0.65) ──┴── 置信度 < τ
                                              │                    │
                                              │           LLM 零样本分类 (few-shot)
                                              │                    │
                                              ▼                    ▼
                                         IntentRouter ──────────────────→ 目标处理链
```

**核心设计理念**：
- **向量分类为主**：复用已引入的 `text-embedding-v4` 和 Milvus，零额外依赖
- **LLM 零样本兜底**：当向量匹配置信度不足时，用轻量模型（qwen-turbo）做 few-shot 分类
- **正则为快速辅助通道**：仅用于问候等极低延迟场景（不作为主分类手段）

---

## 3. 意图分类体系（Intent Taxonomy）

### 3.1 意图分类总览

| 意图 ID | 名称 | 描述 | 示例用户输入 |
|---------|------|------|-------------|
| `KNOWLEDGE_QA` | 知识问答 | 查询内部知识库、SOP、最佳实践 | "CPU 高怎么处理"、"怎么排查慢响应" |
| `ALERT_DIAGNOSIS` | 告警诊断 | 要求分析当前告警、生成诊断报告 | "分析一下当前告警"、"帮我看看有什么问题" |
| `LOG_QUERY` | 日志查询 | 查询腾讯云 CLS 日志 | "查一下最近一小时的 error 日志" |
| `METRICS_QUERY` | 指标查询 | 查询 Prometheus 监控指标或告警 | "当前有哪些告警"、"CPU 使用率是多少" |
| `TIME_QUERY` | 时间查询 | 查询当前时间/日期 | "现在几点"、"今天是几号" |
| `CHITCHAT` | 闲聊 | 不涉及工具调用的通用对话 | "你好"、"你是谁"、"介绍一下自己" |
| `SYSTEM_OPERATION` | 系统操作 | 系统维护、会话管理 | "清空历史记录" |
| `AMBIGUOUS` | 不明确 | 无法确定意图，需澄清 | 模糊输入或混合意图 |

### 3.2 意图-处理链映射

```
意图                    处理链                        模型参数              可用工具
─────────────────────────────────────────────────────────────────────────────────────
KNOWLEDGE_QA    ──→  RAG增强对话链                   qwen-plus  temp=0.5   InternalDocs
ALERT_DIAGNOSIS ──→  AIOps多Agent链                  qwen-plus  temp=0.3   Prometheus+Logs+Docs
LOG_QUERY       ──→  日志查询工具链                   qwen-plus  temp=0.3   LogsTools+MCP
METRICS_QUERY   ──→  指标查询工具链                   qwen-plus  temp=0.3   PrometheusTools
TIME_QUERY      ──→  时间工具链                       qwen-turbo temp=0.1   DateTimeTools
CHITCHAT        ──→  纯LLM对话链（无工具、无RAG）     qwen-turbo temp=0.8   无
SYSTEM_OPERATION ──→ 系统操作链（直接调用服务）       不需要 LLM            直接服务调用
AMBIGUOUS       ──→  澄清追问链（单轮）               qwen-turbo temp=0.5   无
```

---

## 4. 核心分类器：Embedding + Milvus 向量搜索

### 4.1 设计思路

复用项目已有的 `text-embedding-v4` 和 Milvus 基础设施：

1. 为每个意图准备 **5-10 条代表性示例查询**（seed queries）
2. 将这些示例向量化后存入 Milvus（单独 collection 或通过 type 字段区分）
3. 用户输入查询时，先向量化，再在意图示例库中搜索最近邻
4. 返回最匹配的意图及其相似度分数

**为什么这是最合适的方式？**
- 项目已引入 `text-embedding-v4` + Milvus — 零新增基础设施
- 向量匹配延迟低（单次搜索 < 50ms），远低于一次 LLM 调用
- 新增意图只需要添加示例查询，无需改代码
- 冷启动友好：每个意图 5 条示例即可工作

### 4.2 Milvus 存储设计

方案 A（推荐）：**新建 `intent_examples` collection**

```
collection: intent_examples
  fields:
    - id:         long (auto_id)
    - intent:     varchar(32)    // 意图枚举名，如 "KNOWLEDGE_QA"
    - example:    varchar(512)   // 示例查询原文
    - embedding:  float_vector[1024]  // text-embedding-v4 输出的 1024 维向量
  index:
    - index_type: IVF_FLAT
    - metric_type: COSINE  (与现有 RAG 保持一致)
```

方案 B：**复用现有 `biz` collection，通过 `type=intent_example` 字段区分**

```
存入 "biz" 集合，chunk_text 保存示例查询
metadata 中包含 {"type": "intent_example", "intent": "KNOWLEDGE_QA"}
查询时 filter: type == "intent_example"
```

两种方案均可，推荐方案 A 隔离性更好，意图示例不受 RAG 文档影响。

### 4.3 示例查询（Seed Queries）

每个意图预置 5-10 条真实用户可能输入的示例：

```yaml
KNOWLEDGE_QA:
  - "CPU 使用率过高怎么处理"
  - "磁盘满了怎么办"
  - "服务不可用排查步骤"
  - "慢响应问题的最佳实践"
  - "内存泄漏如何排查"
  - "SOP 在哪里查看"

ALERT_DIAGNOSIS:
  - "分析一下当前告警"
  - "帮我看看系统有什么问题"
  - "检查所有活跃告警"
  - "自动诊断一下"
  - "告警分析报告"
  - "运维巡检"

LOG_QUERY:
  - "查一下最近一小时的 error 日志"
  - "查询今天的日志"
  - "看看有没有报错"
  - "日志里有什么异常"
  - "检查应用日志"

METRICS_QUERY:
  - "当前有哪些告警"
  - "CPU 使用率是多少"
  - "内存占用情况"
  - "查看监控指标"
  - "Prometheus 有什么数据"

TIME_QUERY:
  - "现在几点"
  - "今天几号"
  - "星期几"
  - "当前时间"

CHITCHAT:
  - "你好"
  - "你是谁"
  - "你能做什么"
  - "介绍一下你自己"
  - "谢谢"
  - "再见"

SYSTEM_OPERATION:
  - "清空历史记录"
  - "删除会话"
  - "开始新对话"
  - "重置"
```

**持续优化**：在 Phase 2 中，将分类置信度低且正确意图已知的 case 自动添加为新的示例，形成闭环。

### 4.4 分类流程

```java
public class EmbeddingIntentClassifier implements IntentClassifier {
    
    // 余弦相似度阈值，低于此值触发 LLM 兜底
    private static final double CONFIDENCE_THRESHOLD = 0.62;
    
    @Override
    public IntentResult classify(String userInput, List<Map<String, String>> history) {
        // 1. 向量化用户输入
        float[] queryVector = embeddingService.embed(userInput);
        
        // 2. 在意图示例库中搜索
        SearchResult result = vectorSearchService.searchIntentExamples(queryVector, 1);
        
        // 3. 检查置信度
        if (result.getScore() >= CONFIDENCE_THRESHOLD) {
            return IntentResult.builder()
                .intent(result.getIntent())
                .confidence(result.getScore())
                .method("embedding")
                .build();
        }
        
        // 4. 置信度不足，尝试上下文推断
        IntentResult contextResult = inferFromContext(history, result);
        if (contextResult != null) {
            return contextResult;
        }
        
        // 5. 返回低置信度结果，由 HybridIntentClassifier 决定是否走 LLM 兜底
        return IntentResult.builder()
            .intent(result.getIntent())  // 最佳猜测
            .confidence(result.getScore())
            .method("embedding_low_confidence")
            .build();
    }
}
```

---

## 5. 多轮上下文增强

### 5.1 问题：第二轮意图丢失

```
用户: "CPU 使用率是多少"    → METRICS_QUERY ✓
用户: "内存呢"              → 独立分类可能分到 KNOWLEDGE_QA 或 AMBIGUOUS ✗
```

### 5.2 解决方案

会话 Session 中记录当前意图，用三种策略处理：

```java
public class SessionIntentTracker {
    
    // 策略 A: 继承策略（推荐）
    // 当新分类的置信度 < 0.6 且历史中存在高置信度意图时，继承历史意图
    public UserIntent resolveWithHistory(
            IntentResult current, 
            List<IntentResult> history) {
        
        if (current.confidence() >= 0.6) {
            return current.intent();  // 高置信度，直接使用
        }
        
        // 查找最近的稳定意图
        for (int i = history.size() - 1; i >= 0; i--) {
            IntentResult prev = history.get(i);
            if (prev.confidence() >= 0.6 && !prev.isAmbiguous()) {
                // 继承历史意图，但降低置信度
                return prev.intent();
            }
        }
        
        // 策略 B: 文本相似度启发式
        // 当前输入非常短 (< 5 字)，且历史意图明确
        if (isShortElision(current.rawInput()) && !history.isEmpty()) {
            return history.get(history.size() - 1).intent();
        }
        
        return current.intent();
    }
}
```

### 5.3 Session 中的意图流

```java
public class IntentAwareSessionInfo {
    private String sessionId;
    private List<Map<String, String>> messageHistory;
    private List<IntentRecord> intentHistory;  // 新增：意图流历史
    
    public record IntentRecord(
        UserIntent intent,
        double confidence,
        String method,     // "embedding" | "llm" | "context_inherit"
        String userInput,
        long timestamp
    );
}
```

---

## 6. LLM 零样本兜底分类

### 6.1 触发条件

当向量搜索的置信度 < 0.62 时，调用 LLM 零样本分类器。

### 6.2 LLM 分类器设计

```java
@Component
public class LlmZeroShotClassifier implements IntentClassifier {
    
    private static final String SYSTEM_PROMPT = """
        你是一个用户意图分类器。请从以下类别中选择最匹配用户意图的一项：

        KNOWLEDGE_QA - 询问内部知识库、SOP、处理方案、最佳实践
        ALERT_DIAGNOSIS - 要求分析监控告警、生成诊断报告
        LOG_QUERY - 要求查询日志
        METRICS_QUERY - 查询监控指标或告警状态
        TIME_QUERY - 询问当前时间或日期
        CHITCHAT - 闲聊、问候、不涉及工具调用的对话
        SYSTEM_OPERATION - 系统操作、会话管理
        AMBIGUOUS - 以上都不确定

        规则：
        - 优先选择 KNOWLEDGE_QA（用户大概率在问知识）
        - 只有明确提到"日志"/"log"时选择 LOG_QUERY
        - 只有明确提到"告警/指标/监控/使用率"时选择 METRICS_QUERY
        - 只有明确要求"分析/诊断/检查"系统时选择 ALERT_DIAGNOSIS

        输出严格 JSON 格式：
        {"intent": "INTENT_NAME", "confidence": 0.xx, "reason": "简短原因"}
        """;
    
    public IntentResult classify(String userInput, List<Map<String, String>> history) {
        ChatModel liteModel = chatService.createChatModel(api, 0.1, 256, 0.5);
        
        String userPrompt = buildFewShotPrompt(userInput);
        String response = liteModel.call(userPrompt);
        
        return parseJsonResponse(response);
    }
    
    private String buildFewShotPrompt(String input) {
        // 动态注入 2-3 条与输入最相似的 few-shot 示例
        // 从 Milvus 意图示例库中检索 top3 示例及其意图标签
        List<IntentExample> examples = retrieveSimilarExamples(input);
        
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n\n参考示例：\n");
        for (IntentExample ex : examples) {
            sb.append("输入：").append(ex.query()).append("\n");
            sb.append("意图：").append(ex.intent()).append("\n\n");
        }
        sb.append("用户输入：").append(input);
        return sb.toString();
    }
}
```

**关键设计**：few-shot 示例从 Milvus 中动态检索与当前输入最相似的示例（而非固定几条），使 LLM 分类能参考最相关的上下文。

---

## 7. HybridIntentClassifier：三层融合

### 7.1 流程

```
                    ┌──────────────────┐
                    │   用户输入         │
                    └────────┬─────────┘
                             │
               ┌─────────────▼──────────────┐
               │  快速正则通道（可选）         │
               │  "你好"、"现在几点" 等        │
               └─────────────┬──────────────┘
                             │ 未命中
               ┌─────────────▼──────────────┐
               │  Embedding + Milvus 搜索    │  ← 主分类器
               │  置信度 ≥ 0.62?             │
               └─────────────┬──────────────┘
                    ┌────┴────┐
                    │ YES     │ NO
                    │         ▼
                    │     ┌──────────────────────┐
                    │     │  多轮上下文推断        │  ← 利用历史意图
                    │     │  意图继承?             │
                    │     └──────────┬───────────┘
                    │          ┌────┴────┐
                    │          │ YES     │ NO
                    │          │         ▼
                    │          │     ┌──────────────────────┐
                    │          │     │  LLM 零样本分类      │  ← 兜底
                    │          │     └──────────┬───────────┘
                    │          │               │
                    ▼          ▼               ▼
                    ┌──────────────────────────┐
                    │      IntentRouter         │
                    │      ─> 目标处理链         │
                    └──────────────────────────┘
```

### 7.2 融合分类器实现

```java
@Component
public class HybridIntentClassifier {
    
    @Autowired
    private EmbeddingIntentClassifier embeddingClassifier;
    
    @Autowired
    private LlmZeroShotClassifier llmClassifier;
    
    @Autowired
    private SessionIntentTracker intentTracker;
    
    public IntentResult classify(
            String userInput, 
            String sessionId,
            List<Map<String, String>> history) {
        
        // 1. 快速正则通道（仅 CHITCHAT/TIME_QUERY）
        IntentResult quickMatch = quickRegexMatch(userInput);
        if (quickMatch != null) return quickMatch;
        
        // 2. Embedding + Milvus 主分类
        IntentResult embeddingResult = embeddingClassifier.classify(userInput, history);
        
        // 3. 高置信度直接返回
        if (embeddingResult.confidence() >= 0.62 
            && embeddingResult.intent() != UserIntent.AMBIGUOUS) {
            return embeddingResult;
        }
        
        // 4. 尝试多轮上下文继承
        IntentResult contextResult = intentTracker.resolveWithHistory(
            embeddingResult, sessionId);
        if (contextResult != null && contextResult.confidence() >= 0.55) {
            return contextResult;
        }
        
        // 5. LLM 零样本兜底
        IntentResult llmResult = llmClassifier.classify(userInput, history);
        return llmResult;
    }
}
```

---

## 8. IntentRouter：意图路由

### 8.1 路由实现

```java
@Component
public class IntentRouter {
    
    public ResponseEntity<?> route(
            IntentResult intent, 
            ChatRequest request, 
            String sessionId) {
        
        logger.info("意图路由: sessionId={}, intent={}, confidence={}, method={}",
            sessionId, intent.intent(), intent.confidence(), intent.method());
        
        // 记录意图流
        recordIntent(sessionId, intent);
        
        return switch (intent.getIntent()) {
            case KNOWLEDGE_QA -> 
                handleWithTools(request, ALL_TOOLS, RAG_ENABLED);
            case ALERT_DIAGNOSIS -> 
                handleAiOps();
            case LOG_QUERY -> 
                handleWithTools(request, LOG_TOOLS_ONLY, RAG_DISABLED);
            case METRICS_QUERY -> 
                handleWithTools(request, METRICS_TOOLS_ONLY, RAG_DISABLED);
            case TIME_QUERY -> 
                handleWithTools(request, TIME_TOOLS_ONLY, RAG_DISABLED);
            case CHITCHAT -> 
                handleDirectChat(request);
            case SYSTEM_OPERATION -> 
                handleSystemOp(request);
            case AMBIGUOUS -> 
                handleAmbiguous(request);
        };
    }
    
    private ResponseEntity<?> handleWithTools(
            ChatRequest request, 
            ToolFilter toolFilter, 
            boolean enableRag) {
        // 根据意图构建轻量 ReactAgent
        String systemPrompt = buildIntentSpecificPrompt(intent, enableRag);
        ReactAgent agent = buildLightAgent(systemPrompt, toolFilter);
        // ...
    }
}
```

### 8.2 ToolFilter：按意图筛选工具

当前所有工具都注册到 ReactAgent，由 LLM 自行选择。按意图路由后，可以按需注册工具子集：

```java
public enum ToolFilter {
    ALL_TOOLS,
    LOG_TOOLS_ONLY,      // QueryLogsTools + MCP
    METRICS_TOOLS_ONLY,  // QueryMetricsTools
    TIME_TOOLS_ONLY,     // DateTimeTools
    INTENRAL_DOCS_ONLY,  // InternalDocsTools
    NO_TOOLS;            // 纯对话
}
```

---

## 9. 实现路线图

| 阶段 | 内容 | 工作量 | 影响 |
|------|------|--------|------|
| **Phase 1** | 初始化 intent_examples collection + 写入种子数据 + EmbeddingIntentClassifier | 2天 | 核心能力，向量分类可用 |
| **Phase 2** | SessionIntentTracker + 多轮上下文继承 + 意图流记录 | 2天 | 解决第二轮"内存呢"类问题 |
| **Phase 3** | LlmZeroShotClassifier + HybridIntentClassifier 融合 | 3天 | 兜底覆盖 95%+ 场景 |
| **Phase 4** | IntentRouter + 按 intent 裁剪工具 + 定向处理链 | 3天 | 真正的降本增效 |
| **Phase 5** | 分类日志分析 + 混淆案例自动添加 + 意图监控 | 2天 | 持续优化闭环 |

### 建议执行路径

```
Phase 1 ──→ Phase 2 ──→ Phase 4 ──→ Phase 3 ──→ Phase 5
           （先做上下文）   （先见收益）   （LLM兜底不急）
```

**推荐先做 Phase 1→2→4**：在不上 LLM 分类器的情况下，纯靠向量分类 + 上下文继承 + 工具裁剪就能覆盖绝大多数场景并带来可见收益。Phase 3 的 LLM 兜底在观察到向量分类覆盖率不足时再补。

---

## 10. 文件清单

| 文件 | 作用 | 新增/修改 |
|------|------|----------|
| `service/intent/UserIntent.java` | 意图枚举 | 新增 |
| `service/intent/IntentResult.java` | 意图识别结果 | 新增 |
| `service/intent/IntentRecord.java` | 意图流记录 | 新增 |
| `service/intent/IntentClassifier.java` | 分类器接口 | 新增 |
| `service/intent/EmbeddingIntentClassifier.java` | 向量主分类器 | 新增 |
| `service/intent/LlmZeroShotClassifier.java` | LLM 兜底分类器 | 新增 |
| `service/intent/HybridIntentClassifier.java` | 融合分类器 | 新增 |
| `service/intent/SessionIntentTracker.java` | 多轮上下文跟踪 | 新增 |
| `service/intent/IntentRouter.java` | 意图路由 | 新增 |
| `service/intent/ToolFilter.java` | 工具筛选器 | 新增 |
| `service/intent/IntentExampleInitializer.java` | 种子示例初始化 | 新增 |
| `client/MilvusClientFactory.java` | 改造：支持 intent_examples 集合 | 修改 |
| `constant/MilvusConstants.java` | 改造：添加 intent_examples 常量 | 修改 |
| `controller/ChatController.java` | 改造：集成意图识别和路由 | 修改 |
| `service/ChatService.java` | 改造：支持按意图构建 Agent | 修改 |
| `monitor/TokenUsageRecorder.java` | 改造：添加意图埋点 | 修改 |

---

## 11. 效果预估

### 11.1 Token 节省

| 场景 | 当前方案 | 优化方案 | 单次节省 |
|------|---------|---------|---------|
| 问候 "你好" | ReactAgent ~500 tokens → 回答 | 直接回答 ~50 tokens | ~450 tokens |
| 时间查询 "现在几点" | ReactAgent ~800 tokens | TIME 定向工具 ~200 tokens | ~600 tokens |
| 闲聊 "你是谁" | ReactAgent+RAG ~1200 tokens | 无工具无RAG ~100 tokens | ~1100 tokens |
| 日志查询 "查日志" | ReactAgent 全工具 ~600 tokens | LOG 定向工具 ~200 tokens | ~400 tokens |

按日均 1000 次对话估算，Phase 4 后每月节省约 **15-25M tokens**。

### 11.2 用户体验提升

- 闲聊场景首 Token 延迟：~3s → ~0.5s（跳过 RAG 检索和工具注册）
- AIOps 自然语言触发：不再需要点击按钮
- 连续查询的第二轮响应：不再重复分类

---

## 12. 风险与注意事项

1. **冷启动**：新 collection 在第一次部署时为空，需确保 `IntentExampleInitializer` 在应用启动时写入种子数据

2. **灰度策略**：建议先在 `/api/chat_stream` 上实验意图识别，用 `X-Intent-Debug` 响应头返回识别结果，前端可选择展示供调试

3. **混合意图**：本方案按单意图处理。后续可考虑支持优先级规则（如 ALERT_DIAGNOSIS > KNOWLEDGE_QA）

4. **Embedding 漂移**：`text-embedding-v4` 模型升级后需重新向量化所有示例。建议在配置中保留模型版本号

5. **不降级原则**：意图识别是优化层，不是守卫层。即使分类彻底失败，也应回退到当前的全工具 ReactAgent 流程，确保不影响用户使用
