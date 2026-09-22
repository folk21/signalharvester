package io.signalharvester.configuration.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.configuration.MonitoringProfileAnalysisDefaultsConfiguration;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.Update;

/** Jdbi persistence adapter for configuration-owned Monitoring Profiles. */
@Singleton
public final class JdbiMonitoringProfileRepository implements MonitoringProfileRepository {

    private static final String SQL_PATH = "configuration/monitoring-profile";
    private static final String FIND_ALL_SQL = SqlResources.load(SQL_PATH, "find-all");
    private static final String FIND_ENABLED_SQL = SqlResources.load(SQL_PATH, "find-enabled");
    private static final String FIND_BY_ID_SQL = SqlResources.load(SQL_PATH, "find-by-id");
    private static final String INSERT_SQL = SqlResources.load(SQL_PATH, "insert");
    private static final String UPDATE_SQL = SqlResources.load(SQL_PATH, "update");
    private static final String DELETE_SQL = SqlResources.load(SQL_PATH, "delete");
    private static final String REFERENCES_SOURCE_SQL = SqlResources.load(SQL_PATH, "references-source");
    private static final String LOAD_SOURCES_SQL = SqlResources.load(SQL_PATH, "load-sources");
    private static final String LOAD_CRITERIA_SQL = SqlResources.load(SQL_PATH, "load-criteria");
    private static final String LOAD_ANALYSIS_KEYWORDS_SQL =
            SqlResources.load(SQL_PATH, "load-analysis-keywords");
    private static final String DELETE_SOURCES_SQL = SqlResources.load(SQL_PATH, "delete-sources");
    private static final String DELETE_CRITERIA_SQL = SqlResources.load(SQL_PATH, "delete-criteria");
    private static final String DELETE_ANALYSIS_KEYWORDS_SQL =
            SqlResources.load(SQL_PATH, "delete-analysis-keywords");
    private static final String INSERT_SOURCE_SQL = SqlResources.load(SQL_PATH, "insert-source");
    private static final String INSERT_CRITERION_SQL = SqlResources.load(SQL_PATH, "insert-criterion");
    private static final String INSERT_ANALYSIS_KEYWORD_SQL =
            SqlResources.load(SQL_PATH, "insert-analysis-keyword");

    private final Jdbi jdbi;
    private final MonitoringProfileAnalysisDefaultsConfiguration analysisDefaults;

    public JdbiMonitoringProfileRepository(
            @Named("default") Jdbi jdbi,
            MonitoringProfileAnalysisDefaultsConfiguration analysisDefaults) {
        this.jdbi = jdbi;
        this.analysisDefaults = analysisDefaults;
    }

    @Override
    public List<ConfiguredMonitoringProfile> findAll() {
        return execute("Failed to read monitoring profiles", handle -> find(handle, FIND_ALL_SQL, Optional.empty()));
    }

    @Override
    public List<ConfiguredMonitoringProfile> findEnabled() {
        return execute("Failed to read monitoring profiles", handle -> find(handle, FIND_ENABLED_SQL, Optional.empty()));
    }

    @Override
    public Optional<ConfiguredMonitoringProfile> findById(MonitoringProfileId profileId) {
        List<ConfiguredMonitoringProfile> profiles = execute(
                "Failed to read monitoring profiles",
                handle -> find(handle, FIND_BY_ID_SQL, Optional.of(profileId)));
        return profiles.stream().findFirst();
    }

    @Override
    public void insert(ConfiguredMonitoringProfile profile) {
        executeVoid("Failed to insert monitoring profile", handle -> {
            bindProfile(handle.createUpdate(INSERT_SQL), profile).execute();
            replaceChildren(handle, profile);
        });
    }

    @Override
    public boolean update(ConfiguredMonitoringProfile profile) {
        return execute("Failed to update monitoring profile", handle -> {
            int updated = bindProfile(handle.createUpdate(UPDATE_SQL), profile).execute();
            if (updated == 0) {
                return false;
            }
            replaceChildren(handle, profile);
            return true;
        });
    }

    @Override
    public boolean delete(MonitoringProfileId profileId) {
        return execute("Failed to delete monitoring profile", handle ->
                handle.createUpdate(DELETE_SQL).bind("profileId", profileId.value()).execute() > 0);
    }

    @Override
    public boolean referencesSource(SourceId sourceId) {
        return execute("Failed to inspect monitoring-profile source membership", handle ->
                handle.createQuery(REFERENCES_SOURCE_SQL)
                        .bind("sourceId", sourceId.value())
                        .mapTo(Integer.class)
                        .findFirst()
                        .isPresent());
    }

    private List<ConfiguredMonitoringProfile> find(
            Handle handle,
            String sql,
            Optional<MonitoringProfileId> profileId) {
        var query = handle.createQuery(sql);
        profileId.ifPresent(id -> query.bind("profileId", id.value()));
        List<ProfileRow> rows = query.map((resultSet, context) -> mapProfileRow(resultSet)).list();

        List<ConfiguredMonitoringProfile> profiles = new ArrayList<>(rows.size());
        for (ProfileRow row : rows) {
            profiles.add(new ConfiguredMonitoringProfile(
                    row.id(),
                    row.name(),
                    row.informationCategory(),
                    row.enabled(),
                    row.collectionIntervalMinutes(),
                    loadSources(handle, row.id()),
                    loadCriteria(handle, row.id()),
                    loadAnalysisSettings(handle, row.id(), row.analysisMinimumMatches())));
        }
        return List.copyOf(profiles);
    }

    private static ProfileRow mapProfileRow(ResultSet rows) throws SQLException {
        return new ProfileRow(
                MonitoringProfileId.of(rows.getObject("id", UUID.class)),
                rows.getString("name"),
                rows.getString("information_category"),
                rows.getBoolean("enabled"),
                rows.getInt("collection_interval_minutes"),
                rows.getObject("analysis_minimum_matches", Integer.class));
    }

    private static List<SourceId> loadSources(Handle handle, MonitoringProfileId id) {
        return handle.createQuery(LOAD_SOURCES_SQL)
                .bind("profileId", id.value())
                .map((rows, context) -> SourceId.of(rows.getObject("source_id", UUID.class)))
                .list();
    }

    private static Map<String, String> loadCriteria(Handle handle, MonitoringProfileId id) {
        Map<String, String> criteria = new LinkedHashMap<>();
        handle.createQuery(LOAD_CRITERIA_SQL)
                .bind("profileId", id.value())
                .map((rows, context) -> Map.entry(rows.getString("criterion_key"), rows.getString("criterion_value")))
                .forEach(entry -> criteria.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(criteria);
    }

    private MonitoringProfileAnalysisSettings loadAnalysisSettings(
            Handle handle,
            MonitoringProfileId id,
            Integer minimumMatches) {
        List<String> keywords = loadAnalysisKeywords(handle, id);
        if (minimumMatches == null && keywords.isEmpty()) {
            return new MonitoringProfileAnalysisSettings(
                    analysisDefaults.getKeywords(), analysisDefaults.getMinimumMatches());
        }
        if (minimumMatches == null) {
            throw new SourcePersistenceException(
                    "Monitoring profile has incomplete persisted Analysis settings: " + id.value(),
                    new IllegalStateException("analysis minimum must be persisted when Analysis settings are explicit"));
        }
        try {
            return new MonitoringProfileAnalysisSettings(keywords, minimumMatches);
        } catch (IllegalArgumentException exception) {
            throw new SourcePersistenceException(
                    "Monitoring profile has invalid persisted Analysis settings: " + id.value(), exception);
        }
    }

    private static List<String> loadAnalysisKeywords(Handle handle, MonitoringProfileId id) {
        return handle.createQuery(LOAD_ANALYSIS_KEYWORDS_SQL)
                .bind("profileId", id.value())
                .mapTo(String.class)
                .list();
    }

    private static Update bindProfile(Update update, ConfiguredMonitoringProfile profile) {
        return update.bind("id", profile.id().value())
                .bind("name", profile.name())
                .bind("informationCategory", profile.informationCategory())
                .bind("enabled", profile.enabled())
                .bind("collectionIntervalMinutes", profile.collectionIntervalMinutes())
                .bind("analysisMinimumMatches", profile.analysisSettings().minimumMatches());
    }

    private static void replaceChildren(Handle handle, ConfiguredMonitoringProfile profile) {
        handle.createUpdate(DELETE_SOURCES_SQL).bind("profileId", profile.id().value()).execute();
        handle.createUpdate(DELETE_CRITERIA_SQL).bind("profileId", profile.id().value()).execute();
        handle.createUpdate(DELETE_ANALYSIS_KEYWORDS_SQL).bind("profileId", profile.id().value()).execute();

        PreparedBatch sourceBatch = handle.prepareBatch(INSERT_SOURCE_SQL);
        for (int index = 0; index < profile.sourceIds().size(); index++) {
            sourceBatch.bind("profileId", profile.id().value())
                    .bind("sourceId", profile.sourceIds().get(index).value())
                    .bind("sourceOrdinal", index)
                    .add();
        }
        sourceBatch.execute();

        PreparedBatch criterionBatch = handle.prepareBatch(INSERT_CRITERION_SQL);
        profile.criteria().forEach((key, value) -> criterionBatch.bind("profileId", profile.id().value())
                .bind("criterionKey", key)
                .bind("criterionValue", value)
                .add());
        criterionBatch.execute();

        PreparedBatch keywordBatch = handle.prepareBatch(INSERT_ANALYSIS_KEYWORD_SQL);
        for (int index = 0; index < profile.analysisSettings().keywords().size(); index++) {
            keywordBatch.bind("profileId", profile.id().value())
                    .bind("keywordOrdinal", index)
                    .bind("keyword", profile.analysisSettings().keywords().get(index))
                    .add();
        }
        keywordBatch.execute();
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(operation::apply);
        } catch (SourcePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SourcePersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(operation::accept);
        } catch (SourcePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SourcePersistenceException(message, exception);
        }
    }

    @FunctionalInterface
    private interface HandleFunction<T> {
        T apply(Handle handle);
    }

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(Handle handle);
    }

    private record ProfileRow(
            MonitoringProfileId id,
            String name,
            String informationCategory,
            boolean enabled,
            int collectionIntervalMinutes,
            Integer analysisMinimumMatches) {
    }
}
