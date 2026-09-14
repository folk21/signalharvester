package io.signalharvester.eventobservation.application;

/** Signals that retained Event Observation history cannot find the requested flow scope. */
public final class ProcessingFlowNotFoundException extends RuntimeException {

    public ProcessingFlowNotFoundException(String message) {
        super(message);
    }
}
