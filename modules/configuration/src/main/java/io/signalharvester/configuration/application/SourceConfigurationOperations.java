package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.operations.api.OperationalChangeContext;
import java.util.List;

/** Internal application boundary for administering configured external sources. */
public interface SourceConfigurationOperations {

    /** Creates a source without asserting that the caller belongs to the supported journaled mutation path. */
    default ConfiguredSource create(SourceConfigurationCommand command) {
        return create(command, OperationalChangeContext.untrackedSystem());
    }

    /** Creates and journals a source under the supplied operational context. */
    ConfiguredSource create(SourceConfigurationCommand command, OperationalChangeContext changeContext);

    List<ConfiguredSource> list();
    ConfiguredSource get(SourceId sourceId);

    /** Updates a source without asserting that the caller belongs to the supported journaled mutation path. */
    default ConfiguredSource update(SourceId sourceId, SourceConfigurationCommand command) {
        return update(sourceId, command, OperationalChangeContext.untrackedSystem());
    }

    /** Replaces and journals an existing source. */
    ConfiguredSource update(SourceId sourceId, SourceConfigurationCommand command, OperationalChangeContext changeContext);

    /** Deletes a source without asserting that the caller belongs to the supported journaled mutation path. */
    default void delete(SourceId sourceId) {
        delete(sourceId, OperationalChangeContext.untrackedSystem());
    }

    /** Deletes and journals an existing source. */
    void delete(SourceId sourceId, OperationalChangeContext changeContext);
}
