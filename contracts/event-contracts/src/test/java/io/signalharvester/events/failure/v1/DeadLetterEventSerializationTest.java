package io.signalharvester.events.failure.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies round-trip serialization of {@link DeadLetterEvent} diagnostic and replay metadata.
 *
 * <p>Related specification: {@code backend-reliability-failure-handling}.</p>
 *
 * <p>Features: {@code CONTRACTS.KAFKA_PROTOBUF}, {@code RELIABILITY.DEAD_LETTER}.</p>
 */
class DeadLetterEventSerializationTest {

    private static final int UNKNOWN_FIELD_NUMBER = 1000;
    private static final String UNKNOWN_FIELD_VALUE = "future-field";

    /** Round trip one representative terminal consumer failure. */
    @Test
    void shouldRoundTripRepresentativeDeadLetterEvent() throws Exception {
        DeadLetterEvent original = representativeDeadLetterEvent();

        DeadLetterEvent decoded = DeadLetterEvent.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    /** Preserve forward-compatible unknown additive fields on the terminal failure contract. */
    @Test
    void shouldTolerateUnknownAdditiveFields() throws Exception {
        DeadLetterEvent original = representativeDeadLetterEvent();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(original.toByteArray());

        CodedOutputStream codedOutput = CodedOutputStream.newInstance(bytes);
        codedOutput.writeString(UNKNOWN_FIELD_NUMBER, UNKNOWN_FIELD_VALUE);
        codedOutput.flush();

        DeadLetterEvent decoded = DeadLetterEvent.parseFrom(bytes.toByteArray());

        assertEquals(original.getDeadLetterId(), decoded.getDeadLetterId());
        assertEquals(original.getSourcePayload(), decoded.getSourcePayload());
        assertTrue(decoded.getUnknownFields().hasField(UNKNOWN_FIELD_NUMBER));
    }

    private static DeadLetterEvent representativeDeadLetterEvent() {
        return DeadLetterEvent.newBuilder()
                .setDeadLetterId("signalharvester-analysis-v1:signalharvester.collection.raw-item-discovered.v1:2:41")
                .setConsumer("analysis")
                .setConsumerGroup("signalharvester-analysis-v1")
                .setSourceTopic("signalharvester.collection.raw-item-discovered.v1")
                .setSourcePartition(2)
                .setSourceOffset(41L)
                .setSourceKey("raw-01")
                .setSourcePayload(ByteString.copyFromUtf8("payload"))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("database unavailable")
                .setAttempts(3)
                .setRetryable(true)
                .build();
    }
}
