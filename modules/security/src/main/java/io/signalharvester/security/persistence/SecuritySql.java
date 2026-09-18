package io.signalharvester.security.persistence;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

/** Loads Security-owned SQL statements from classpath resources. */
final class SecuritySql {

    private static final ClasspathSqlLocator LOCATOR = ClasspathSqlLocator.create();

    private SecuritySql() {
    }

    /** Loads a user-persistence statement by stable resource name. */
    static String user(String name) {
        return LOCATOR.getResource("sql/security/user/" + name + ".sql");
    }
}
