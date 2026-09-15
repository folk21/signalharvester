package io.signalharvester.events.failure.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/**
 * Verifies round-trip serialization of {@link DeadLetterEvent} diagnostic and replay metadata.
 *
 * <p>Related specification: {@code backend-reliability-failure-handling}.</p>
 */
class DeadLetterEventSerializationTest {

    /** Round trip one representative terminal consumer failure. */
    @Test
    void shouldRoundTripRepresentativeDeadLetterEvent() throws Exception {
        DeadLetterEvent original = DeadLetterEvent.newBuilder()
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

        DeadLetterEvent decoded = DeadLetterEvent.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }
}
