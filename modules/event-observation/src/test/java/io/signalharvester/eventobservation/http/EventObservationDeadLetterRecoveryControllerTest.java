package io.signalharvester.eventobservation.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.eventobservation.application.DeadLetterRecovery;
import io.signalharvester.eventobservation.application.DeadLetterRecoveryException;
import io.signalharvester.eventobservation.event.kafka.KafkaEventObservationDeadLetterRecoveryService;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.application.OperationalChangeJournalService;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies ADMIN dead-letter inspection/replay HTTP behavior for feature {@code RELIABILITY.DEAD_LETTER}. */
class EventObservationDeadLetterRecoveryControllerTest {

    private static final String SPEC_NAME = "event-observation-dead-letter-recovery-controller";
    private static final String DEAD_LETTER_ID = "group:source-topic:1:42";

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.event-observation.enabled", false)), "test");
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Inspect and replay a confirmed record on the blocking executor without exposing source payload bytes. */
    @Test
    void shouldInspectAndReplayConfirmedDeadLetterOnBlockingThread() throws Exception {
        String basePath = "/api/v1/admin/event-observation/dead-letters/0/7";
        HttpResponse<String> inspection = send("GET", basePath, null);
        assertEquals(200, inspection.statusCode());
        assertTrue(inspection.body().contains("\"deadLetterId\":\"" + DEAD_LETTER_ID + "\""));
        assertTrue(inspection.body().contains("\"sourcePayloadBytes\":128"));
        assertFalse(inspection.body().contains("\"sourcePayload\""));

        HttpResponse<String> replay = send(
                "POST",
                basePath + "/replay",
                "{\"expectedDeadLetterId\":\"" + DEAD_LETTER_ID + "\"}");
        assertEquals(200, replay.statusCode());

        TestOperationalChangeJournal journal = server.getApplicationContext().getBean(TestOperationalChangeJournal.class);
        assertEquals(OperationalChangeCategory.DEAD_LETTER_RECOVERY, journal.lastRequest().get().category());
        assertEquals("event-observation:0:7", journal.lastRequest().get().targetId());

        TestDeadLetterRecovery recovery = server.getApplicationContext().getBean(TestDeadLetterRecovery.class);
        assertEquals(DEAD_LETTER_ID, recovery.expectedId().get());
        assertTrue(recovery.lastThread().get().isVirtual());
        assertFalse(recovery.lastThread().get().getName().contains("EventLoop"));
    }

    /** Validate position/confirmation input and map expected recovery failures. */
    @Test
    void shouldValidateAndMapRecoveryFailures() throws Exception {
        String basePath = "/api/v1/admin/event-observation/dead-letters";
        assertEquals(400, send("GET", basePath + "/-1/0", null).statusCode());
        assertEquals(400, send("GET", basePath + "/0/-1", null).statusCode());
        assertEquals(400, send("POST", basePath + "/0/7/replay", "{\"expectedDeadLetterId\":\" \"}").statusCode());
        assertEquals(404, send("GET", basePath + "/0/404", null).statusCode());
        assertEquals(422, send("GET", basePath + "/0/422", null).statusCode());
        assertEquals(429, send("GET", basePath + "/0/429", null).statusCode());
        assertEquals(503, send("GET", basePath + "/0/503", null).statusCode());
        assertEquals(409, send("POST", basePath + "/0/409/replay", "{\"expectedDeadLetterId\":\"other\"}").statusCode());
        assertEquals(409, send("POST", basePath + "/0/410/replay",
                "{\"expectedDeadLetterId\":\"" + DEAD_LETTER_ID + "\"}").statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.getURI().resolve(path));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DeadLetterRecovery.Inspection inspection() {
        return new DeadLetterRecovery.Inspection(
                DEAD_LETTER_ID,
                "event-observation",
                "group",
                "dead-letter-topic",
                0,
                7,
                "source-topic",
                1,
                42,
                "source-key",
                "java.lang.IllegalStateException",
                "failure",
                3,
                true,
                128);
    }

    @Singleton
    @Replaces(OperationalChangeJournalService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestOperationalChangeJournal implements OperationalChangeJournal {
        private final AtomicReference<OperationalChangeRequest> lastRequest = new AtomicReference<>();

        @Override
        public OperationalChangeRecord record(OperationalChangeRequest request) {
            lastRequest.set(request);
            return new OperationalChangeRecord(
                    java.util.UUID.randomUUID(),
                    java.time.Instant.now(),
                    request.category(),
                    request.targetType(),
                    request.targetId(),
                    request.beforeState(),
                    request.afterState(),
                    request.outcome(),
                    request.context().source(),
                    request.context().actorId(),
                    request.context().correlationId(),
                    "",
                    "test");
        }

        @Override
        public OperationalChangeRecord recordInCurrentTransaction(OperationalChangeRequest request) {
            return record(request);
        }

        AtomicReference<OperationalChangeRequest> lastRequest() {
            return lastRequest;
        }
    }

    @Singleton
    @Replaces(KafkaEventObservationDeadLetterRecoveryService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestDeadLetterRecovery implements DeadLetterRecovery {
        private final AtomicReference<String> expectedId = new AtomicReference<>();
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public Inspection inspect(int deadLetterPartition, long deadLetterOffset) {
            lastThread.set(Thread.currentThread());
            if (deadLetterOffset == 404) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.NOT_FOUND, "missing dead letter");
            }
            if (deadLetterOffset == 422) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.INVALID_RECORD, "invalid dead letter");
            }
            if (deadLetterOffset == 429) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.BUSY, "recovery busy");
            }
            if (deadLetterOffset == 503) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.KAFKA_UNAVAILABLE, "kafka unavailable");
            }
            return inspection();
        }

        @Override
        public Inspection replay(int deadLetterPartition, long deadLetterOffset, String expectedDeadLetterId) {
            lastThread.set(Thread.currentThread());
            expectedId.set(expectedDeadLetterId);
            if (deadLetterOffset == 409) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.CONFIRMATION_FAILED, "confirmation mismatch");
            }
            if (deadLetterOffset == 410) {
                throw new DeadLetterRecoveryException(
                        DeadLetterRecoveryException.Reason.REPLAY_FAILED, "replay failed");
            }
            return inspection();
        }

        AtomicReference<String> expectedId() {
            return expectedId;
        }

        AtomicReference<Thread> lastThread() {
            return lastThread;
        }
    }
}
