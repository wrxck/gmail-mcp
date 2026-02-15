package com.gmail.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GmailMcpServer {

    private static final Logger log = LoggerFactory.getLogger(GmailMcpServer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 10;

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "--auth".equals(args[0])) {
                GmailAuth.authorize();
                System.err.println("Authorization successful. You can now start the MCP server.");
                return;
            }

            var credential = GmailAuth.getCredential();
            var client = new GmailClient(
                    credential, GmailAuth.getHttpTransport(), GmailAuth.getJsonFactory());

            startServer(client);
        } catch (Exception e) {
            log.error("Failed to start Gmail MCP server", e);
            System.exit(1);
        }
    }

    private static void startServer(GmailClient client) {
        var transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(OBJECT_MAPPER));

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("gmail", "1.0.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(
                        buildListEmailsTool(client),
                        buildReadEmailTool(client),
                        buildSearchEmailsTool(client),
                        buildListLabelsTool(client))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Gmail MCP server");
            server.close();
        }));

        log.info("Gmail MCP server started");
    }

    private static McpServerFeatures.SyncToolSpecification buildListEmailsTool(GmailClient client) {
        var schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "maxResults", Map.of("type", "integer", "description",
                                "Maximum number of emails to return (default 10, max 100)"),
                        "query", Map.of("type", "string", "description",
                                "Gmail search query (e.g. 'from:someone@example.com')"),
                        "label", Map.of("type", "string", "description",
                                "Filter by label (e.g. 'INBOX', 'SENT')")
                ),
                Collections.emptyList(),
                false, null, null
        );

        var tool = McpSchema.Tool.builder()
                .name("list_emails")
                .description("List recent emails from Gmail. Supports optional search query and label filter.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = safeArgs(request);
                        int maxResults = parseMaxResults(args);
                        String query = (String) args.get("query");
                        String label = (String) args.get("label");

                        if (label != null && !label.isBlank()) {
                            String labelQuery = "label:" + label;
                            query = (query != null && !query.isBlank())
                                    ? query + " " + labelQuery : labelQuery;
                        }

                        var messages = client.listMessages(query, maxResults);
                        return jsonResult(messages);
                    } catch (Exception e) {
                        log.error("list_emails failed", e);
                        return errorResult("Error listing emails: " + e.getMessage());
                    }
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification buildReadEmailTool(GmailClient client) {
        var schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", Map.of("type", "string", "description", "The email message ID")
                ),
                List.of("id"),
                false, null, null
        );

        var tool = McpSchema.Tool.builder()
                .name("read_email")
                .description("Read the full content of an email by its message ID.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = safeArgs(request);
                        String id = (String) args.get("id");
                        if (id == null || id.isBlank()) {
                            return errorResult("'id' is required");
                        }

                        var message = client.getMessage(id);
                        return jsonResult(message);
                    } catch (Exception e) {
                        log.error("read_email failed", e);
                        return errorResult("Error reading email: " + e.getMessage());
                    }
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification buildSearchEmailsTool(GmailClient client) {
        var schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "query", Map.of("type", "string", "description",
                                "Gmail search query (e.g. 'from:john subject:meeting after:2024/01/01')"),
                        "maxResults", Map.of("type", "integer", "description",
                                "Maximum number of results (default 10, max 100)")
                ),
                List.of("query"),
                false, null, null
        );

        var tool = McpSchema.Tool.builder()
                .name("search_emails")
                .description("Search emails using Gmail search syntax. Supports all Gmail search operators.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = safeArgs(request);
                        String query = (String) args.get("query");
                        if (query == null || query.isBlank()) {
                            return errorResult("'query' is required");
                        }

                        int maxResults = parseMaxResults(args);
                        var messages = client.searchMessages(query, maxResults);
                        return jsonResult(messages);
                    } catch (Exception e) {
                        log.error("search_emails failed", e);
                        return errorResult("Error searching emails: " + e.getMessage());
                    }
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification buildListLabelsTool(GmailClient client) {
        var schema = new McpSchema.JsonSchema(
                "object",
                Collections.emptyMap(),
                Collections.emptyList(),
                false, null, null
        );

        var tool = McpSchema.Tool.builder()
                .name("list_labels")
                .description("List all Gmail labels (inbox, sent, custom labels, etc.)")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var labels = client.listLabels();
                        return jsonResult(labels);
                    } catch (Exception e) {
                        log.error("list_labels failed", e);
                        return errorResult("Error listing labels: " + e.getMessage());
                    }
                })
                .build();
    }

    private static Map<String, Object> safeArgs(McpSchema.CallToolRequest request) {
        return request.arguments() != null ? request.arguments() : Map.of();
    }

    private static int parseMaxResults(Map<String, Object> args) {
        Object raw = args.get("maxResults");
        if (raw instanceof Number n) {
            return n.intValue();
        } else if (raw instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return DEFAULT_MAX_RESULTS;
            }
        }
        return DEFAULT_MAX_RESULTS;
    }

    private static CallToolResult jsonResult(Object data) throws Exception {
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        return CallToolResult.builder().addTextContent(json).build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }
}
