package io.signalharvester.eventobservation.application;

import io.signalharvester.eventobservation.model.ObservedEventInput;

/** Internal application boundary for idempotent recording of decoded Kafka events. */
public interface EventObservationRecorder {
    void record(ObservedEventInput event);
}
