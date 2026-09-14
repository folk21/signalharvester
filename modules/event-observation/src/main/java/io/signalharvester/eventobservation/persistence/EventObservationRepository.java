package io.signalharvester.eventobservation.persistence;

import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import java.time.Instant;
import java.util.List;

/** Persistence port for bounded technical event history. */
public interface EventObservationRepository {
    void insert(ObservedEventInput event);
    void pruneBefore(Instant cutoff);
    void pruneToMaxEvents(int maxEvents);
    List<ObservedEvent> findRecent(EventObservationCriteria criteria, int limit);
    long currentCursor();
    List<ObservedEvent> findAfter(long cursor, long throughCursor, EventObservationCriteria criteria, int limit);
}
