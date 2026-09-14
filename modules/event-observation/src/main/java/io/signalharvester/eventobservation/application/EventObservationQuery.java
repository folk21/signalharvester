package io.signalharvester.eventobservation.application;

import io.signalharvester.eventobservation.model.ObservedEvent;
import java.util.List;

/** Internal application boundary for bounded technical event history queries. */
public interface EventObservationQuery {

    List<ObservedEvent> recent(EventObservationCriteria criteria, int limit);

    long currentCursor();

    EventObservationLiveBatch pollAfter(long cursor, EventObservationCriteria criteria, int limit);
}
