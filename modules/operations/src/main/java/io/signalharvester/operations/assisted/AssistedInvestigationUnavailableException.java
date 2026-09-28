package io.signalharvester.operations.assisted;

/** Explicit provider invocation was requested while no assisted-investigation provider is configured. */
public final class AssistedInvestigationUnavailableException extends RuntimeException {
    public AssistedInvestigationUnavailableException(String message) {
        super(message);
    }
}
