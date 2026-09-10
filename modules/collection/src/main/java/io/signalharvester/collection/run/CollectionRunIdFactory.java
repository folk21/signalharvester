package io.signalharvester.collection.run;

import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Generates collection-run identifiers behind a deterministic test seam.
 */
@Singleton
public final class CollectionRunIdFactory {

    private final Supplier<UUID> idSupplier;

    public CollectionRunIdFactory() {
        this(UUID::randomUUID);
    }

    CollectionRunIdFactory(Supplier<UUID> idSupplier) {
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    /**
     * Creates a new globally unique collection-run identifier.
     *
     * @return collection-run identifier
     */
    public String nextId() {
        return Objects.requireNonNull(idSupplier.get(), "collection run id supplier returned null").toString();
    }
}
