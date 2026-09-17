package io.signalharvester.configuration.persistence;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.configuration.MonitoringProfileAnalysisDefaultsConfiguration;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC persistence adapter for configuration-owned monitoring profiles. */
@Singleton
public final class JdbcMonitoringProfileRepository implements MonitoringProfileRepository {
    private final DataSource dataSource;
    private final MonitoringProfileAnalysisDefaultsConfiguration analysisDefaults;

    public JdbcMonitoringProfileRepository(
            @Named("default") DataSource dataSource,
            MonitoringProfileAnalysisDefaultsConfiguration analysisDefaults) {
        this.dataSource = dataSource;
        this.analysisDefaults = analysisDefaults;
    }

    @Override
    public List<ConfiguredMonitoringProfile> findAll() {
        return find("SELECT id, name, information_category, enabled, collection_interval_minutes, analysis_minimum_matches "
                + "FROM configuration.monitoring_profiles ORDER BY name, id", null);
    }

    @Override
    public List<ConfiguredMonitoringProfile> findEnabled() {
        return find("SELECT id, name, information_category, enabled, collection_interval_minutes, analysis_minimum_matches "
                + "FROM configuration.monitoring_profiles WHERE enabled = TRUE ORDER BY name, id", null);
    }

    @Override
    public Optional<ConfiguredMonitoringProfile> findById(MonitoringProfileId profileId) {
        List<ConfiguredMonitoringProfile> profiles = find(
                "SELECT id, name, information_category, enabled, collection_interval_minutes, analysis_minimum_matches "
                        + "FROM configuration.monitoring_profiles WHERE id = ?",
                profileId);
        return profiles.stream().findFirst();
    }

    @Override
    public void insert(ConfiguredMonitoringProfile profile) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profiles "
                                + "(id, name, information_category, enabled, collection_interval_minutes, analysis_minimum_matches) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            bindProfile(statement, profile);
            statement.executeUpdate();
            replaceChildren(connection, profile);
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to insert monitoring profile", exception);
        }
    }

    @Override
    public boolean update(ConfiguredMonitoringProfile profile) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE configuration.monitoring_profiles SET name = ?, information_category = ?, enabled = ?, "
                                + "collection_interval_minutes = ?, analysis_minimum_matches = ? WHERE id = ?")) {
            statement.setString(1, profile.name());
            statement.setString(2, profile.informationCategory());
            statement.setBoolean(3, profile.enabled());
            statement.setInt(4, profile.collectionIntervalMinutes());
            statement.setInt(5, profile.analysisSettings().minimumMatches());
            statement.setObject(6, profile.id().value());
            if (statement.executeUpdate() == 0) {
                return false;
            }
            replaceChildren(connection, profile);
            return true;
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to update monitoring profile", exception);
        }
    }

    @Override
    public boolean delete(MonitoringProfileId profileId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM configuration.monitoring_profiles WHERE id = ?")) {
            statement.setObject(1, profileId.value());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to delete monitoring profile", exception);
        }
    }

    @Override
    public boolean referencesSource(SourceId sourceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM configuration.monitoring_profile_sources WHERE source_id = ? LIMIT 1")) {
            statement.setObject(1, sourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to inspect monitoring-profile source membership", exception);
        }
    }

    private List<ConfiguredMonitoringProfile> find(String sql, MonitoringProfileId profileId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (profileId != null) {
                statement.setObject(1, profileId.value());
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<ConfiguredMonitoringProfile> profiles = new ArrayList<>();
                while (rows.next()) {
                    MonitoringProfileId id = MonitoringProfileId.of(rows.getObject("id", java.util.UUID.class));
                    Integer minimumMatches = rows.getObject("analysis_minimum_matches", Integer.class);
                    profiles.add(new ConfiguredMonitoringProfile(
                            id,
                            rows.getString("name"),
                            rows.getString("information_category"),
                            rows.getBoolean("enabled"),
                            rows.getInt("collection_interval_minutes"),
                            loadSources(connection, id),
                            loadCriteria(connection, id),
                            loadAnalysisSettings(connection, id, minimumMatches)));
                }
                return List.copyOf(profiles);
            }
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to read monitoring profiles", exception);
        }
    }

    private static List<SourceId> loadSources(Connection connection, MonitoringProfileId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_id FROM configuration.monitoring_profile_sources "
                        + "WHERE profile_id = ? ORDER BY source_ordinal")) {
            statement.setObject(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<SourceId> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(SourceId.of(rows.getObject("source_id", java.util.UUID.class)));
                }
                return List.copyOf(result);
            }
        }
    }

    private static Map<String, String> loadCriteria(Connection connection, MonitoringProfileId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT criterion_key, criterion_value FROM configuration.monitoring_profile_criteria "
                        + "WHERE profile_id = ? ORDER BY criterion_key")) {
            statement.setObject(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, String> result = new LinkedHashMap<>();
                while (rows.next()) {
                    result.put(rows.getString("criterion_key"), rows.getString("criterion_value"));
                }
                return Map.copyOf(result);
            }
        }
    }

    private MonitoringProfileAnalysisSettings loadAnalysisSettings(
            Connection connection,
            MonitoringProfileId id,
            Integer minimumMatches) throws SQLException {
        List<String> keywords = loadAnalysisKeywords(connection, id);
        if (minimumMatches == null && keywords.isEmpty()) {
            return new MonitoringProfileAnalysisSettings(
                    analysisDefaults.getKeywords(), analysisDefaults.getMinimumMatches());
        }
        if (minimumMatches == null || keywords.isEmpty()) {
            throw new SourcePersistenceException(
                    "Monitoring profile has incomplete persisted Analysis settings: " + id.value(),
                    new IllegalStateException("analysis minimum and keywords must be persisted together"));
        }
        try {
            return new MonitoringProfileAnalysisSettings(keywords, minimumMatches);
        } catch (IllegalArgumentException exception) {
            throw new SourcePersistenceException(
                    "Monitoring profile has invalid persisted Analysis settings: " + id.value(), exception);
        }
    }

    private static List<String> loadAnalysisKeywords(Connection connection, MonitoringProfileId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT keyword FROM configuration.monitoring_profile_analysis_keywords "
                        + "WHERE profile_id = ? ORDER BY keyword_ordinal")) {
            statement.setObject(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<String> keywords = new ArrayList<>();
                while (rows.next()) {
                    keywords.add(rows.getString("keyword"));
                }
                return List.copyOf(keywords);
            }
        }
    }

    private static void bindProfile(PreparedStatement statement, ConfiguredMonitoringProfile profile) throws SQLException {
        statement.setObject(1, profile.id().value());
        statement.setString(2, profile.name());
        statement.setString(3, profile.informationCategory());
        statement.setBoolean(4, profile.enabled());
        statement.setInt(5, profile.collectionIntervalMinutes());
        statement.setInt(6, profile.analysisSettings().minimumMatches());
    }

    private static void replaceChildren(Connection connection, ConfiguredMonitoringProfile profile) throws SQLException {
        try (PreparedStatement deleteSources = connection.prepareStatement(
                        "DELETE FROM configuration.monitoring_profile_sources WHERE profile_id = ?");
                PreparedStatement deleteCriteria = connection.prepareStatement(
                        "DELETE FROM configuration.monitoring_profile_criteria WHERE profile_id = ?");
                PreparedStatement deleteKeywords = connection.prepareStatement(
                        "DELETE FROM configuration.monitoring_profile_analysis_keywords WHERE profile_id = ?")) {
            deleteSources.setObject(1, profile.id().value());
            deleteSources.executeUpdate();
            deleteCriteria.setObject(1, profile.id().value());
            deleteCriteria.executeUpdate();
            deleteKeywords.setObject(1, profile.id().value());
            deleteKeywords.executeUpdate();
        }
        try (PreparedStatement insertSource = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profile_sources (profile_id, source_id, source_ordinal) VALUES (?, ?, ?)");
                PreparedStatement insertCriterion = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profile_criteria (profile_id, criterion_key, criterion_value) VALUES (?, ?, ?)");
                PreparedStatement insertKeyword = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profile_analysis_keywords (profile_id, keyword_ordinal, keyword) VALUES (?, ?, ?)")) {
            for (int index = 0; index < profile.sourceIds().size(); index++) {
                insertSource.setObject(1, profile.id().value());
                insertSource.setObject(2, profile.sourceIds().get(index).value());
                insertSource.setInt(3, index);
                insertSource.addBatch();
            }
            insertSource.executeBatch();
            for (Map.Entry<String, String> criterion : profile.criteria().entrySet()) {
                insertCriterion.setObject(1, profile.id().value());
                insertCriterion.setString(2, criterion.getKey());
                insertCriterion.setString(3, criterion.getValue());
                insertCriterion.addBatch();
            }
            insertCriterion.executeBatch();
            for (int index = 0; index < profile.analysisSettings().keywords().size(); index++) {
                insertKeyword.setObject(1, profile.id().value());
                insertKeyword.setInt(2, index);
                insertKeyword.setString(3, profile.analysisSettings().keywords().get(index));
                insertKeyword.addBatch();
            }
            insertKeyword.executeBatch();
        }
    }
}
