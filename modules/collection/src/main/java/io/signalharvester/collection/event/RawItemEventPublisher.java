package io.signalharvester.collection.event;

import io.signalharvester.collection.source.FetchedSourceContent;

/**
 * Publishes fetched external content to the asynchronous raw-item event boundary.
 *
 * <p>The acknowledged implementation is blocking and must be invoked from a blocking/Virtual-Thread
 * workflow rather than directly from a Netty event-loop thread.</p>
 */
public interface RawItemEventPublisher {

    /**
     * Publishes one fetched payload and waits until Kafka acknowledges the record.
     *
     * @param content fetched source payload
     * @param context raw-item identity, correlation, and classification metadata supplied by collection orchestration
     * @return identifiers and topic of the acknowledged publication
     * @throws RawItemPublicationException when mapping, serialization, or Kafka publication fails
     */
    RawItemPublicationResult publish(FetchedSourceContent content, RawItemPublicationContext context);
}
