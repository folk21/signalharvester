package io.signalharvester.collection.source.extract;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import java.util.List;

/** Internal collection boundary that converts one fetched source response into semantic raw items. */
public interface SourceItemExtractor {

    /** Extracts zero or more semantic items from one successful source response. */
    List<ExtractedSourceItem> extract(ConfiguredSource source, FetchedSourceContent fetchedContent);
}
