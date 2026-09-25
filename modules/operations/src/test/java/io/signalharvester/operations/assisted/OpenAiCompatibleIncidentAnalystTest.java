package io.signalharvester.operations.assisted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.signalharvester.operations.assisted.tools.InvestigationToolDefinition;
import io.signalharvester.operations.assisted.tools.InvestigationToolName;
import io.signalharvester.operations.assisted.tools.InvestigationToolRequest;
import io.signalharvester.operations.assisted.tools.InvestigationToolResult;
import io.signalharvester.operations.assisted.tools.InvestigationToolSession;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleIncidentAnalystTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldExecuteBoundedToolCallBeforeFinalAssessment() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int request = requests.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request == 1) {
                assertTrue(requestBody.contains("\"tools\""));
                respond(exchange, """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call-health-1","type":"function","function":{"name":"health_context","arguments":"{}"}}
                        ]}}]}
                        """);
            } else {
                assertTrue(requestBody.contains("UNTRUSTED READ-ONLY EVIDENCE"));
                respond(exchange, """
                        {"choices":[{"message":{"role":"assistant","content":"{\\\"summary\\\":\\\"Tool evidence confirms the selected snapshot.\\\",\\\"suspectedSubsystems\\\":[\\\"operations\\\"],\\\"confidence\\\":0.8,\\\"observations\\\":[\\\"Health context was inspected.\\\"],\\\"hypotheses\\\":[],\\\"evidenceReferences\\\":[\\\"health-snapshot:00000000-0000-0000-0000-000000000123\\\"],\\\"recommendedChecks\\\":[\\\"Continue read-only telemetry review.\\\"],\\\"humanAttentionSuggested\\\":false}"}}]}
                        """);
            }
        });
        server.start();

        UUID snapshotId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        TestConfiguration configuration = new TestConfiguration(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        OpenAiCompatibleIncidentAnalyst analyst = new OpenAiCompatibleIncidentAnalyst(
                configuration,
                HttpClient.newHttpClient(),
                new ObjectMapper());
        RecordingToolSession tools = new RecordingToolSession(snapshotId);
        HealthAnalysisPackage analysisPackage = new HealthAnalysisPackage(
                "health-analysis-v1",
                snapshotId,
                Instant.parse("2026-09-25T08:00:00Z"),
                "Analyze this bounded health report.",
                List.of("health-snapshot:" + snapshotId),
                false);

        IncidentInvestigationResult result = analyst.investigate(analysisPackage, tools);

        assertEquals(2, requests.get());
        assertEquals(1, result.toolCallCount());
        assertEquals(2, result.roundCount());
        assertEquals(List.of("health-snapshot:" + snapshotId), tools.discoveredEvidenceReferences());
        assertEquals("Tool evidence confirms the selected snapshot.", result.assessment().summary());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class RecordingToolSession implements InvestigationToolSession {
        private final UUID snapshotId;
        private int calls;

        private RecordingToolSession(UUID snapshotId) {
            this.snapshotId = snapshotId;
        }

        @Override
        public List<InvestigationToolDefinition> definitions() {
            return List.of(new InvestigationToolDefinition(
                    InvestigationToolName.HEALTH_CONTEXT,
                    "Return bounded health context.",
                    Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
        }

        @Override
        public InvestigationToolResult execute(InvestigationToolRequest request) {
            calls++;
            return new InvestigationToolResult(
                    request.callId(),
                    request.tool(),
                    true,
                    "UNTRUSTED READ-ONLY EVIDENCE\n{\"status\":\"UNKNOWN\"}",
                    List.of("health-snapshot:" + snapshotId));
        }

        @Override
        public List<String> discoveredEvidenceReferences() {
            return List.of("health-snapshot:" + snapshotId);
        }

        @Override
        public int toolCallCount() {
            return calls;
        }
    }

    private record TestConfiguration(String endpoint) implements AssistedInvestigationConfiguration {
        @Override public String getProvider() { return "openai-compatible"; }
        @Override public String getEndpoint() { return endpoint; }
        @Override public String getApiKey() { return ""; }
        @Override public String getModel() { return "test-model"; }
        @Override public Duration getRequestTimeout() { return Duration.ofSeconds(5); }
        @Override public int getMaxReportChars() { return 50_000; }
        @Override public int getMaxResponseBytes() { return 32_768; }
        @Override public int getMaxRounds() { return 4; }
        @Override public int getMaxToolCalls() { return 4; }
        @Override public Duration getMaxInvestigationDuration() { return Duration.ofSeconds(10); }
        @Override public int getMaxToolResultChars() { return 12_000; }
        @Override public Duration getMaxTelemetryLookback() { return Duration.ofMinutes(30); }
        @Override public int getMaxLogEntries() { return 50; }
        @Override public int getMaxTraceResults() { return 20; }
        @Override public String getLokiBaseUrl() { return ""; }
        @Override public String getTempoBaseUrl() { return ""; }
        @Override public int getAssessmentRetentionCount() { return 500; }
    }
}
