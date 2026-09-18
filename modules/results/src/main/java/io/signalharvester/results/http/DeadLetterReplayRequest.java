package io.signalharvester.results.http;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit operator confirmation required before replaying one Results dead-letter record. */
@Serdeable
public record DeadLetterReplayRequest(
        @NotBlank @Size(max = 1024) String expectedDeadLetterId) {
}
