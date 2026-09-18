package io.signalharvester.analysis.persistence;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Analysis persistence SQL statements from classpath resources. */
final class AnalysisPersistenceSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private AnalysisPersistenceSql() {
    }

    /** Loads a deduplication statement by stable resource name. */
    static String deduplication(String name) {
        return LOCATOR.getResource("sql/analysis/deduplication/" + name + ".sql");
    }

    /** Loads an inspection statement by stable resource name. */
    static String inspection(String name) {
        return LOCATOR.getResource("sql/analysis/inspection/" + name + ".sql");
    }
}
