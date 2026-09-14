package io.signalharvester.eventobservation.http;

import io.micronaut.serde.annotation.Serdeable;

/** Kafka transport metadata attached to one observed technical event. */
@Serdeable
public record ObservedKafkaMetadataResponse(String topic, int partition, long offset, String key) {}
