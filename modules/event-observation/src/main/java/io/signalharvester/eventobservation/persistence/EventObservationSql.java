package io.signalharvester.eventobservation.persistence;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Event Observation persistence SQL statements from classpath resources. */
final class EventObservationSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private EventObservationSql() {
    }

    /** Loads an Event Observation persistence statement by stable resource name. */
    static String get(String name) {
        return LOCATOR.getResource("sql/event-observation/" + name + ".sql");
    }
}
