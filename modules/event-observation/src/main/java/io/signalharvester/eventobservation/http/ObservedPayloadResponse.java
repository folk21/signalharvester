package io.signalharvester.eventobservation.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Human-readable selected payload fields decoded from a supported Protobuf event. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ObservedPayloadResponse(
        String type,
        @Nullable String sourceEventId,
        @Nullable String rawItemId,
        @Nullable String normalizedItemId,
        @Nullable String sourceId,
        @Nullable String monitoringProfileId,
        @Nullable String informationCategory,
        @Nullable String externalId,
        @Nullable String title,
        @Nullable String url,
        @Nullable String contentType,
        @Nullable Boolean relevant,
        @Nullable String classification,
        @Nullable Integer score,
        @Nullable String analyzer,
        @Nullable String reasonCode,
        @Nullable String explanation) {}
