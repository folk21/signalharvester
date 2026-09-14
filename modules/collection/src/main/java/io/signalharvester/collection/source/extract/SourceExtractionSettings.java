package io.signalharvester.collection.source.extract;

/** Setting names understood by configuration-driven JSON and HTML extractors. */
final class SourceExtractionSettings {

    static final String JSON_PREFIX = "json.";
    static final String JSON_ITEMS_POINTER = "json.itemsPointer";
    static final String JSON_EXTERNAL_ID_POINTER = "json.externalIdPointer";
    static final String JSON_TITLE_POINTER = "json.titlePointer";
    static final String JSON_URL_POINTER = "json.urlPointer";
    static final String JSON_CONTENT_POINTER = "json.contentPointer";
    static final String JSON_PUBLISHED_AT_POINTER = "json.publishedAtPointer";

    static final String HTML_PREFIX = "html.";
    static final String HTML_ITEM_SELECTOR = "html.itemSelector";
    static final String HTML_EXTERNAL_ID_SELECTOR = "html.externalIdSelector";
    static final String HTML_EXTERNAL_ID_ATTRIBUTE = "html.externalIdAttribute";
    static final String HTML_TITLE_SELECTOR = "html.titleSelector";
    static final String HTML_URL_SELECTOR = "html.urlSelector";
    static final String HTML_URL_ATTRIBUTE = "html.urlAttribute";
    static final String HTML_CONTENT_SELECTOR = "html.contentSelector";
    static final String HTML_PUBLISHED_AT_SELECTOR = "html.publishedAtSelector";
    static final String HTML_PUBLISHED_AT_ATTRIBUTE = "html.publishedAtAttribute";

    private SourceExtractionSettings() {
    }
}
