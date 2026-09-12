package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.api.AnalysisItemInspection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.analysis.model.NormalizedContentItem;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class DeduplicationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/analysis"),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.analysis.keyword-rules.keywords[0]", "java"),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void shouldDeduplicateWithinProfileAndAllowSameLogicalItemForAnotherProfile() throws Exception {
        DeduplicationClaimRepository repository = context.getBean(DeduplicationClaimRepository.class);
        Instant firstSeen = Instant.parse("2026-09-10T18:00:00Z");
        NormalizedContentItem first = item("profile-a", "raw-01", "event-01");
        NormalizedContentItem duplicate = item("profile-a", "raw-02", "event-02");
        NormalizedContentItem otherProfile = item("profile-b", "raw-03", "event-03");

        assertTrue(repository.tryClaim(first, firstSeen));
        assertFalse(repository.tryClaim(duplicate, firstSeen.plusSeconds(10)));
        repository.recordDuplicate(duplicate, firstSeen.plusSeconds(10));
        assertTrue(repository.tryClaim(otherProfile, firstSeen.plusSeconds(20)));

        AnalysisItemInspectionRepository inspection = context.getBean(AnalysisItemInspectionRepository.class);
        assertEquals(2, inspection.findRecent(10, Optional.empty(), Optional.empty()).size());
        AnalysisItemInspection inspected = inspection.find(
                "profile-a",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").orElseThrow();
        assertEquals(2, inspected.discoveryCount());
        assertEquals("raw-02", inspected.lastRawItemId());

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT monitoring_profile_id, discovery_count, last_raw_item_id
                          FROM analysis.normalized_item_claims
                         ORDER BY monitoring_profile_id
                        """)) {
            assertTrue(resultSet.next());
            assertEquals("profile-a", resultSet.getString("monitoring_profile_id"));
            assertEquals(2, resultSet.getLong("discovery_count"));
            assertEquals("raw-02", resultSet.getString("last_raw_item_id"));
            assertTrue(resultSet.next());
            assertEquals("profile-b", resultSet.getString("monitoring_profile_id"));
            assertEquals(1, resultSet.getLong("discovery_count"));
            assertFalse(resultSet.next());
        }
    }

    private static NormalizedContentItem item(String profileId, String rawItemId, String sourceEventId) {
        return new NormalizedContentItem(
                sourceEventId,
                "run-01",
                Optional.empty(),
                Instant.parse("2026-09-10T18:00:00Z"),
                rawItemId,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "source-01",
                profileId,
                "JOB",
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                "Java Kafka",
                "text/plain",
                Map.of(),
                Optional.empty());
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
