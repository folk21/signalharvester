package io.signalharvester.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic classpath SQL resource resolution by {@link SqlResources}.
 *
 * <p>Related feature: {@code TESTING.DETERMINISTIC_LOCAL}.</p>
 */
class SqlResourcesTest {

    /** Append the shared SQL extension and load the requested resource text. */
    @Test
    void shouldLoadSqlResourceByDirectoryAndStatementName() {
        assertEquals("SELECT 1;\n", SqlResources.load("test", "sample"));
    }

    /** Resolve SQL independently of the calling thread context classloader. */
    @Test
    void shouldIgnoreThreadContextClassloaderWhenResolvingSql() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        try {
            assertEquals("SELECT 1;\n", SqlResources.load("test", "sample"));
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /** Reject missing SQL resources with the fully resolved classpath path. */
    @Test
    void shouldRejectMissingSqlResource() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SqlResources.load("test", "missing"));

        assertEquals("SQL resource not found: sql/test/missing.sql", exception.getMessage());
    }
}
