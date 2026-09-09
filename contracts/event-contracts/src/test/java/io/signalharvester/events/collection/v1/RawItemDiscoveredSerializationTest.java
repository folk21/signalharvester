package io.signalharvester.events.collection.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RawItemDiscoveredSerializationTest {

    @Test
    void shouldRoundTripRepresentativeEvent() throws IOException {
        RawItemDiscovered original = representativeEvent();

        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    @Test
    void shouldTolerateUnknownAdditiveFields() throws IOException {
        RawItemDiscovered original = representativeEvent();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(original.toByteArray());

        CodedOutputStream codedOutput = CodedOutputStream.newInstance(bytes);
        codedOutput.writeString(1000, "future-field");
        codedOutput.flush();

        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(bytes.toByteArray());

        assertEquals(original.getRawItemId(), decoded.getRawItemId());
        assertEquals(original.getEnvelope().getEventId(), decoded.getEnvelope().getEventId());
        assertTrue(decoded.getUnknownFields().hasField(1000));
    }

    private static RawItemDiscovered representativeEvent() {
        Timestamp occurredAt = Timestamp.newBuilder()
                .setSeconds(1_725_817_600L)
                .build();

        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventId("event-01")
                .setEventType("collection.raw-item-discovered.v1")
                .setOccurredAt(occurredAt)
                .setCorrelationId("run-01")
                .setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .setProducer("collection")
                .setSchemaVersion("v1")
                .build();

        return RawItemDiscovered.newBuilder()
                .setEnvelope(envelope)
                .setRawItemId("raw-01")
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
                .setInformationCategory("JOB")
                .setExternalId("external-42")
                .setTitle("Senior Java Backend Engineer")
                .setUrl("https://example.test/jobs/42")
                .setContent("Java, Kafka and PostgreSQL")
                .setContentType("text/plain")
                .setPublishedAt(occurredAt)
                .build();
    }
}
