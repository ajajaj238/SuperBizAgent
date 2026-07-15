package org.example.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

public class McpToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Function<JsonNode, String> handler;

    public McpToolDefinition(String name,
                             String description,
                             Map<String, Object> inputSchema,
                             Function<JsonNode, String> handler) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.handler = handler;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    public String call(JsonNode arguments) {
        return handler.apply(arguments);
    }
}
