package io.signalharvester.operations.assisted.tools;

/** Raised when a model attempts to exceed application-owned tool-call or investigation-duration bounds. */
public final class InvestigationBudgetExceededException extends RuntimeException {
    public InvestigationBudgetExceededException(String message) {
        super(message);
    }
}
