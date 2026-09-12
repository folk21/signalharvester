package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.model.NormalizedContentItem;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * PostgreSQL adapter that enforces one accepted logical item per monitoring-profile deduplication scope.
 */
@Singleton
public final class JdbcDeduplicationClaimRepository implements DeduplicationClaimRepository {

    private final DataSource dataSource;

    public JdbcDeduplicationClaimRepository(@Named("default") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean tryClaim(NormalizedContentItem item, Instant seenAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO analysis.normalized_item_claims (
                            monitoring_profile_id,
                            normalized_item_id,
                            source_id,
                            external_id,
                            source_url,
                            first_raw_item_id,
                            first_source_event_id,
                            first_seen_at,
                            last_raw_item_id,
                            last_source_event_id,
                            last_seen_at,
                            discovery_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                        ON CONFLICT (monitoring_profile_id, normalized_item_id) DO NOTHING
                        """)) {
            bindIdentity(statement, item, seenAt);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new AnalysisPersistenceException("Failed to claim normalized item", exception);
        }
    }

    @Override
    public void recordDuplicate(NormalizedContentItem item, Instant seenAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE analysis.normalized_item_claims
                           SET last_raw_item_id = ?,
                               last_source_event_id = ?,
                               last_seen_at = ?,
                               discovery_count = discovery_count + 1
                         WHERE monitoring_profile_id = ?
                           AND normalized_item_id = ?
                        """)) {
            statement.setString(1, item.rawItemId());
            statement.setString(2, item.sourceEventId());
            statement.setTimestamp(3, Timestamp.from(seenAt));
            statement.setString(4, item.monitoringProfileId());
            statement.setString(5, item.normalizedItemId());
            if (statement.executeUpdate() != 1) {
                throw new AnalysisPersistenceException(
                        "Normalized item claim disappeared while recording duplicate",
                        new IllegalStateException("No deduplication claim found"));
            }
        } catch (SQLException exception) {
            throw new AnalysisPersistenceException("Failed to record duplicate discovery", exception);
        }
    }

    private static void bindIdentity(
            PreparedStatement statement,
            NormalizedContentItem item,
            Instant seenAt) throws SQLException {
        statement.setString(1, item.monitoringProfileId());
        statement.setString(2, item.normalizedItemId());
        statement.setString(3, item.sourceId());
        if (item.externalId().isPresent()) {
            statement.setString(4, item.externalId().orElseThrow());
        } else {
            statement.setNull(4, java.sql.Types.VARCHAR);
        }
        statement.setString(5, item.url().toString());
        statement.setString(6, item.rawItemId());
        statement.setString(7, item.sourceEventId());
        statement.setTimestamp(8, Timestamp.from(seenAt));
        statement.setString(9, item.rawItemId());
        statement.setString(10, item.sourceEventId());
        statement.setTimestamp(11, Timestamp.from(seenAt));
    }
}
