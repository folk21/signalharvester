package io.signalharvester.collection.persistence;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Collection run-history SQL statements from classpath resources. */
final class CollectionRunSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private CollectionRunSql() {
    }

    /** Loads a run-history persistence statement by stable resource name. */
    static String get(String name) {
        return LOCATOR.getResource("sql/collection/run-history/" + name + ".sql");
    }
}
