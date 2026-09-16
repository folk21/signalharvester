package io.signalharvester.collection.source.access;

/** Reports that an external-source destination was rejected before connection establishment. */
public final class OutboundAccessDeniedException extends RuntimeException {

    public OutboundAccessDeniedException(String message) {
        super(message);
    }
}
