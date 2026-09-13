package io.signalharvester.collection.run;


/** Records completed collection runs through the collection-owned persistence boundary. */
@FunctionalInterface
public interface CollectionRunHistoryRecorder {
    void record(CollectionRunResult result);
}
