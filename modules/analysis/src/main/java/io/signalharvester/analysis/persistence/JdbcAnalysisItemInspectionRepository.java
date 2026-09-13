package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.application.AnalysisItemInspection;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** PostgreSQL read adapter for bounded operational inspection of normalized-item claims. */
@Singleton
public final class JdbcAnalysisItemInspectionRepository implements AnalysisItemInspectionRepository {
    private final DataSource dataSource;

    public JdbcAnalysisItemInspectionRepository(@Named("default") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<AnalysisItemInspection> findRecent(
            int limit, Optional<String> monitoringProfileId, Optional<String> sourceId) {
        String sql = """
                SELECT monitoring_profile_id, normalized_item_id, source_id, external_id, source_url,
                       first_raw_item_id, first_source_event_id, first_seen_at,
                       last_raw_item_id, last_source_event_id, last_seen_at, discovery_count
                  FROM analysis.normalized_item_claims
                 WHERE (? IS NULL OR monitoring_profile_id = ?)
                   AND (? IS NULL OR source_id = ?)
                 ORDER BY last_seen_at DESC, normalized_item_id
                 LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String profile = monitoringProfileId.orElse(null);
            String source = sourceId.orElse(null);
            statement.setString(1, profile);
            statement.setString(2, profile);
            statement.setString(3, source);
            statement.setString(4, source);
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AnalysisItemInspection> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(map(rows));
                }
                return List.copyOf(results);
            }
        } catch (SQLException exception) {
            throw new AnalysisPersistenceException("Failed to list analysis item inspection state", exception);
        }
    }

    @Override
    public Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId) {
        String sql = """
                SELECT monitoring_profile_id, normalized_item_id, source_id, external_id, source_url,
                       first_raw_item_id, first_source_event_id, first_seen_at,
                       last_raw_item_id, last_source_event_id, last_seen_at, discovery_count
                  FROM analysis.normalized_item_claims
                 WHERE monitoring_profile_id = ? AND normalized_item_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monitoringProfileId);
            statement.setString(2, normalizedItemId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new AnalysisPersistenceException("Failed to read analysis item inspection state", exception);
        }
    }

    private static AnalysisItemInspection map(ResultSet row) throws SQLException {
        return new AnalysisItemInspection(
                row.getString("monitoring_profile_id"),
                row.getString("normalized_item_id"),
                row.getString("source_id"),
                Optional.ofNullable(row.getString("external_id")),
                row.getString("source_url"),
                row.getString("first_raw_item_id"),
                row.getString("first_source_event_id"),
                row.getTimestamp("first_seen_at").toInstant(),
                row.getString("last_raw_item_id"),
                row.getString("last_source_event_id"),
                row.getTimestamp("last_seen_at").toInstant(),
                row.getLong("discovery_count"));
    }
}
