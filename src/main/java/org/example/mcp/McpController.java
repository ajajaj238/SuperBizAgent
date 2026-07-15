package org.example.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    private static final String JSONRPC = "2.0";
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper objectMapper;
    private final McpToolRegistry toolRegistry;

    @Value("${superbiz.mcp.enabled:true}")
    private boolean enabled;

    @Value("${superbiz.mcp.api-key:}")
    private String apiKey;

    public McpController(ObjectMapper objectMapper, McpToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> describe(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-MCP-API-Key", required = false) String headerApiKey) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!isAuthorized(authorization, headerApiKey)) {
            return unauthorized();
        }

        Map<String, Object> body = Map.of(
                "name", "superbiz-aiops-mcp",
                "version", "1.0.0",
                "protocolVersion", PROTOCOL_VERSION,
                "endpoint", "/mcp",
                "transport", "streamable-http-json-rpc",
                "tools", toolRegistry.listTools()
        );
        return withProtocolHeader(ResponseEntity.ok(body));
    }

    @PostMapping(value = "/mcp", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(
            @RequestBody JsonNode request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-MCP-API-Key", required = false) String headerApiKey) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!isAuthorized(authorization, headerApiKey)) {
            return unauthorized();
        }

        try {
            if (request == null || request.isNull()) {
                return withProtocolHeader(ResponseEntity.ok(error(null, -32700, "Parse error", "Empty request body")));
            }

            if (request.isArray()) {
                ArrayNode responses = objectMapper.createArrayNode();
                for (JsonNode item : request) {
                    JsonNode response = handleSingle(item);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                if (responses.isEmpty()) {
                    return withProtocolHeader(ResponseEntity.accepted().build());
                }
                return withProtocolHeader(ResponseEntity.ok(responses));
            }

            JsonNode response = handleSingle(request);
            if (response == null) {
                return withProtocolHeader(ResponseEntity.accepted().build());
            }
            return withProtocolHeader(ResponseEntity.ok(response));
        } catch (Exception e) {
            logger.warn("event=mcp_request_failed error={}", e.getMessage(), e);
            return withProtocolHeader(ResponseEntity.ok(error(null, -32603, "Internal error", e.getMessage())));
        }
    }

    private JsonNode handleSingle(JsonNode request) {
        JsonNode id = request == null ? null : request.get("id");
        boolean notification = id == null || id.isNull();

        if (request == null || !request.isObject()) {
            return error(id, -32600, "Invalid Request", "JSON-RPC request must be an object");
        }
        if (!JSONRPC.equals(request.path("jsonrpc").asText())) {
            return error(id, -32600, "Invalid Request", "jsonrpc must be 2.0");
        }

        String method = request.path("method").asText("");
        JsonNode params = request.path("params");

        try {
            return switch (method) {
                case "initialize" -> success(id, initializeResult());
                case "ping" -> success(id, objectMapper.createObjectNode());
                case "tools/list" -> success(id, objectMapper.valueToTree(Map.of("tools", toolRegistry.listTools())));
                case "tools/call" -> success(id, callTool(params));
                case "resources/list" -> success(id, objectMapper.valueToTree(Map.of("resources", List.of())));
                case "prompts/list" -> success(id, objectMapper.valueToTree(Map.of("prompts", List.of())));
                case "notifications/initialized", "notifications/cancelled", "notifications/progress" -> null;
                default -> notification
                        ? null
                        : error(id, -32601, "Method not found", "Unsupported MCP method: " + method);
            };
        } catch (IllegalArgumentException e) {
            return notification ? null : error(id, -32602, "Invalid params", e.getMessage());
        } catch (Exception e) {
            return notification ? null : error(id, -32603, "Internal error", e.getMessage());
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", false);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "superbiz-aiops-mcp");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);

        result.put("instructions",
                "Use SuperBizAgent MCP tools for AIOps documentation search, log queries, Prometheus alerts, and alert diagnosis context. Tools are read-only by default.");
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("tools/call params must be an object");
        }
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Missing required params.name");
        }

        JsonNode arguments = params.path("arguments");
        String text = toolRegistry.callTool(name, arguments);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", text == null ? "" : text);
        content.add(textContent);
        result.set("content", content);
        result.put("isError", false);
        return result;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC);
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("result", result == null ? objectMapper.createObjectNode() : result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message, String data) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC);
        response.set("id", id == null ? objectMapper.nullNode() : id);

        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isBlank()) {
            error.put("data", data);
        }
        response.set("error", error);
        return response;
    }

    private boolean isAuthorized(String authorization, String headerApiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        if (apiKey.equals(headerApiKey)) {
            return true;
        }
        return authorization != null && authorization.equals("Bearer " + apiKey);
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", 401, "message", "Unauthorized MCP request"));
    }

    private ResponseEntity<Object> withProtocolHeader(ResponseEntity.BodyBuilder builder) {
        return builder.header("MCP-Protocol-Version", PROTOCOL_VERSION).build();
    }

    private ResponseEntity<Object> withProtocolHeader(ResponseEntity<Object> response) {
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> {
                    headers.addAll(response.getHeaders());
                    headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
                })
                .body(response.getBody());
    }
}
