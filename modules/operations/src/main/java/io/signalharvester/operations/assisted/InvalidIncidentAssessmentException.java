package io.signalharvester.operations.assisted;

/** Structured model/manual assessment violated bounded schema or evidence-reference rules. */
public final class InvalidIncidentAssessmentException extends RuntimeException {
    public InvalidIncidentAssessmentException(String message) {
        super(message);
    }

    public InvalidIncidentAssessmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
