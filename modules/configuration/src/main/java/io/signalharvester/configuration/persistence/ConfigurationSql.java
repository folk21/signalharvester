package io.signalharvester.configuration.persistence;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads configuration-owned SQL statements from classpath resources. */
final class ConfigurationSql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private ConfigurationSql() {
    }

    /** Loads a Source persistence statement by stable resource name. */
    static String source(String name) {
        return LOCATOR.getResource("sql/configuration/source/" + name + ".sql");
    }

    /** Loads a Monitoring Profile persistence statement by stable resource name. */
    static String monitoringProfile(String name) {
        return LOCATOR.getResource("sql/configuration/monitoring-profile/" + name + ".sql");
    }
}
