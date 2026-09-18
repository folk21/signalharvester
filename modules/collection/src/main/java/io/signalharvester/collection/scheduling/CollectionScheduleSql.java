package io.signalharvester.collection.scheduling;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Collection scheduler SQL statements from classpath resources. */
final class CollectionScheduleSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private CollectionScheduleSql() {
    }

    /** Loads a scheduler persistence statement by stable resource name. */
    static String get(String name) {
        return LOCATOR.getResource("sql/collection/schedule/" + name + ".sql");
    }
}
