package io.signalharvester.operations.api;

/** Published synchronous boundary for recording sanitized behavior-affecting operational changes. */
public interface OperationalChangeJournal {

    /** Persists one already-sanitized change record in an Operations-owned transaction. */
    OperationalChangeRecord record(OperationalChangeRequest request);

    /** Persists one already-sanitized change record in the caller's active database transaction. */
    OperationalChangeRecord recordInCurrentTransaction(OperationalChangeRequest request);
}
