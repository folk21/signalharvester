package io.signalharvester.collection.api;

/** Primary synchronous application API for executing one explicit collection run. */
public interface CollectionRunner {

    /**
     * Executes one bounded best-effort collection run over the currently enabled configured sources.
     *
     * @param request caller-owned run context
     * @return durable terminal run snapshot
     */
    CollectionRunResult run(CollectionRunRequest request);
}
