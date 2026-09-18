package io.signalharvester.common.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads SQL text from classpath resources shared by persistence adapters. */
public final class SqlResources {

    private static final String SQL_ROOT = "sql/";
    private static final String SQL_EXT = ".sql";

    private SqlResources() {
    }

    /** Loads one SQL resource from the application classpath. */
    public static String load(String directory, String name) {
        String resourcePath = resourcePath(directory, name);
        try (InputStream stream = SqlResources.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("SQL resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read SQL resource: " + resourcePath, exception);
        }
    }

    private static String resourcePath(String directory, String name) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(name, "name");
        if (directory.isBlank()) {
            throw new IllegalArgumentException("directory must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return SQL_ROOT + directory + '/' + name + SQL_EXT;
    }
}
