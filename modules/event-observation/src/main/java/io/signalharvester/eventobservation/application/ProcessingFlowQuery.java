package io.signalharvester.eventobservation.application;

/** Internal application boundary for bounded processing-flow reconstruction. */
public interface ProcessingFlowQuery {

    ProcessingFlow collectionRun(String collectionRunId);

    ProcessingFlow item(String collectionRunId, String itemId);
}
