package io.signalharvester.collection.event;

import io.signalharvester.collection.source.ExtractedSourceItem;

/**
 * Publishes extracted source items to the asynchronous raw-item event boundary.
 *
 * <p>The acknowledged implementation is blocking and must be invoked from a blocking/Virtual-Thread
 * workflow rather than directly from a Netty event-loop thread.</p>
 */
public interface RawItemEventPublisher {

    /**
     * Publishes one extracted semantic item and waits until Kafka acknowledges the record.
     *
     * @param item collection-owned extracted item
     * @param context raw-item identity, correlation, and classification metadata supplied by collection orchestration
     * @return identifiers and topic of the acknowledged publication
     * @throws RawItemPublicationException when mapping, serialization, or Kafka publication fails
     */
    RawItemPublicationResult publish(ExtractedSourceItem item, RawItemPublicationContext context);
}
