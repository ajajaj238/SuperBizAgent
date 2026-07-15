package org.example.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ObjectMapper objectMapper;
    private final Map<String, McpToolDefinition> tools = new LinkedHashMap<>();

    public McpToolRegistry(ObjectMapper objectMapper,
                           InternalDocsTools internalDocsTools,
                           QueryLogsTools queryLogsTools,
                           QueryMetricsTools queryMetricsTools,
                           DateTimeTools dateTimeTools) {
        this.objectMapper = objectMapper;

        register(new McpToolDefinition(
                "aiops_search_docs",
                "Search SuperBizAgent internal AIOps knowledge base with RAG. Use it for runbooks, troubleshooting steps, fault causes, and internal operations documentation.",
                schema(Map.of(
                        "query", property("string", "Search query, for example: CPU usage is too high troubleshooting steps")
                ), List.of("query")),
                args -> internalDocsTools.queryInternalDocs(requiredText(args, "query"))));

        register(new McpToolDefinition(
                "aiops_get_log_topics",
                "List available log topics before querying logs. Use this to understand valid logTopic values and example queries.",
                schema(Map.of(), List.of()),
                args -> queryLogsTools.getAvailableLogTopics()));

        register(new McpToolDefinition(
                "aiops_query_logs",
                "Query AIOps logs by region, topic, query, and limit. Use it to inspect application logs, system metrics logs, slow query logs, or system events.",
                schema(Map.of(
                        "region", property("string", "Optional region. Valid values: ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu. Default: ap-guangzhou"),
                        "logTopic", property("string", "Required log topic, for example: system-metrics, application-logs, database-slow-query, system-events"),
                        "query", property("string", "Optional Lucene-like query, for example: level:ERROR OR cpu_usage:>80"),
                        "limit", property("integer", "Optional result limit. Default 20, max 100")
                ), List.of("logTopic")),
                args -> queryLogsTools.queryLogs(
                        optionalText(args, "region", "ap-guangzhou"),
                        requiredText(args, "logTopic"),
                        optionalText(args, "query", ""),
                        optionalInt(args, "limit", 20))));

        register(new McpToolDefinition(
                "aiops_query_prometheus_alerts",
                "Query active Prometheus alerts. Use it to inspect currently firing alerts and alert durations.",
                schema(Map.of(), List.of()),
                args -> queryMetricsTools.queryPrometheusAlerts()));

        register(new McpToolDefinition(
                "aiops_get_current_datetime",
                "Get current server date time with timezone.",
                schema(Map.of(), List.of()),
                args -> dateTimeTools.getCurrentDateTime()));

        register(new McpToolDefinition(
                "aiops_diagnose_alert_context",
                "Collect diagnosis context for an AIOps alert. It aggregates active alerts, internal runbooks, and related logs; the MCP host should use the returned context to produce the final analysis.",
                schema(Map.of(
                        "alertName", property("string", "Alert name, for example: HighCPUUsage, HighMemoryUsage, HighDiskUsage, ServiceUnavailable, SlowResponse"),
                        "description", property("string", "Optional alert description or symptom details"),
                        "logTopic", property("string", "Optional log topic. Default: application-logs"),
                        "logQuery", property("string", "Optional log query. Default is derived from alertName and description"),
                        "limit", property("integer", "Optional log limit. Default 10, max 100")
                ), List.of("alertName")),
                args -> diagnoseAlertContext(args, internalDocsTools, queryLogsTools, queryMetricsTools)));
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpToolDefinition tool : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            result.add(item);
        }
        return result;
    }

    public String callTool(String name, JsonNode arguments) {
        McpToolDefinition tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        }
        long startedAt = System.currentTimeMillis();
        try {
            String result = tool.call(arguments == null || arguments.isNull() ? objectMapper.createObjectNode() : arguments);
            logger.info("event=mcp_tool_call tool={} success=true durationMs={}",
                    name, System.currentTimeMillis() - startedAt);
            return result;
        } catch (Exception e) {
            logger.warn("event=mcp_tool_call tool={} success=false durationMs={} error={}",
                    name, System.currentTimeMillis() - startedAt, e.getMessage());
            throw e;
        }
    }

    private void register(McpToolDefinition tool) {
        tools.put(tool.name(), tool);
    }

    private String diagnoseAlertContext(JsonNode args,
                                        InternalDocsTools internalDocsTools,
                                        QueryLogsTools queryLogsTools,
                                        QueryMetricsTools queryMetricsTools) {
        String alertName = requiredText(args, "alertName");
        String description = optionalText(args, "description", "");
        String logTopic = optionalText(args, "logTopic", "application-logs");
        int limit = optionalInt(args, "limit", 10);
        String logQuery = optionalText(args, "logQuery", deriveLogQuery(alertName, description));
        String docsQuery = (alertName + " " + description + " 故障原因 排查步骤 处理预案").trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertName", alertName);
        payload.put("description", description);
        payload.put("activeAlerts", parseJsonOrText(queryMetricsTools.queryPrometheusAlerts()));
        payload.put("runbookDocs", parseJsonOrText(internalDocsTools.queryInternalDocs(docsQuery)));
        payload.put("relatedLogs", parseJsonOrText(queryLogsTools.queryLogs("ap-guangzhou", logTopic, logQuery, limit)));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize alert diagnosis context", e);
        }
    }

    private Object parseJsonOrText(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return text;
        }
    }

    private String deriveLogQuery(String alertName, String description) {
        String base = (alertName + " " + description).toLowerCase();
        if (base.contains("cpu")) {
            return "cpu_usage:>80 OR level:WARN OR level:ERROR";
        }
        if (base.contains("memory") || base.contains("oom")) {
            return "memory_usage:>85 OR oom OR level:ERROR";
        }
        if (base.contains("disk")) {
            return "disk_usage:>90 OR no_space OR level:ERROR";
        }
        if (base.contains("slow") || base.contains("timeout")) {
            return "response_time:>3000 OR timeout OR slow";
        }
        return "level:ERROR OR level:WARN OR " + alertName;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static String requiredText(JsonNode args, String fieldName) {
        String value = optionalText(args, fieldName, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode args, String fieldName, String defaultValue) {
        if (args == null || !args.has(fieldName) || args.get(fieldName).isNull()) {
            return defaultValue;
        }
        return args.get(fieldName).asText(defaultValue);
    }

    private static int optionalInt(JsonNode args, String fieldName, int defaultValue) {
        if (args == null || !args.has(fieldName) || args.get(fieldName).isNull()) {
            return defaultValue;
        }
        int value = args.get(fieldName).asInt(defaultValue);
        return Math.max(1, Math.min(value, 100));
    }
}
