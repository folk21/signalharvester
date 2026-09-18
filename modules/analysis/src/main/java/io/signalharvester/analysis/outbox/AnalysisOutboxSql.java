package io.signalharvester.analysis.outbox;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Analysis outbox SQL statements from classpath resources. */
final class AnalysisOutboxSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private AnalysisOutboxSql() {
    }

    /** Loads an outbox persistence statement by stable resource name. */
    static String get(String name) {
        return LOCATOR.getResource("sql/analysis/outbox/" + name + ".sql");
    }
}
