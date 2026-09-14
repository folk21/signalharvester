package io.signalharvester.collection.source.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Extracts semantic items from REST JSON responses using persisted RFC 6901 JSON Pointer settings. */
@Singleton
public final class JsonSourceItemExtractor {

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GenericExtractionConfiguration configuration;

    public JsonSourceItemExtractor(GenericExtractionConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Returns whether the persisted source settings opt into JSON extraction. */
    boolean isConfigured(ConfiguredSource source) {
        return source.settings().keySet().stream().anyMatch(key -> key.startsWith(SourceExtractionSettings.JSON_PREFIX));
    }

    /** Parses one JSON response into bounded semantic items. */
    List<ExtractedSourceItem> extract(ConfiguredSource source, FetchedSourceContent fetchedContent) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedContent, "fetchedContent");
        Map<String, String> settings = source.settings();
        if (!settings.containsKey(SourceExtractionSettings.JSON_CONTENT_POINTER)) {
            throw new SourceItemExtractionException(
                    source.id(), "JSON extraction requires setting json.contentPointer");
        }

        JsonNode document = parse(source, fetchedContent.body());
        String itemsPointer = settings.getOrDefault(SourceExtractionSettings.JSON_ITEMS_POINTER, "");
        JsonNode candidateNode = at(source, document, itemsPointer, SourceExtractionSettings.JSON_ITEMS_POINTER);
        if (candidateNode.isMissingNode() || candidateNode.isNull()) {
            throw new SourceItemExtractionException(source.id(), "json.itemsPointer did not resolve to a value");
        }

        List<JsonNode> candidates = candidates(source, candidateNode);
        int maxItems = configuration.getMaxItemsPerSource();
        if (candidates.size() > maxItems) {
            throw new SourceItemExtractionException(
                    source.id(),
                    "JSON response contains " + candidates.size()
                            + " candidate items, exceeding configured maximum " + maxItems);
        }

        List<ExtractedSourceItem> items = new ArrayList<>(candidates.size());
        for (JsonNode candidate : candidates) {
            items.add(toItem(source, fetchedContent, candidate));
        }
        return List.copyOf(items);
    }

    private static JsonNode parse(ConfiguredSource source, byte[] body) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(body);
            if (parsed == null) {
                throw new SourceItemExtractionException(source.id(), "JSON response is empty");
            }
            return parsed;
        } catch (IOException failure) {
            throw new SourceItemExtractionException(source.id(), "Failed to parse JSON response", failure);
        }
    }

    private static List<JsonNode> candidates(ConfiguredSource source, JsonNode node) {
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>(node.size());
            node.elements().forEachRemaining(result::add);
            return result;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        throw new SourceItemExtractionException(
                source.id(), "json.itemsPointer must resolve to a JSON array or object");
    }

    private static ExtractedSourceItem toItem(
            ConfiguredSource source,
            FetchedSourceContent fetchedContent,
            JsonNode candidate) {
        Map<String, String> settings = source.settings();
        JsonNode contentNode = at(
                source,
                candidate,
                settings.get(SourceExtractionSettings.JSON_CONTENT_POINTER),
                SourceExtractionSettings.JSON_CONTENT_POINTER);
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new SourceItemExtractionException(source.id(), "json.contentPointer did not resolve for a candidate item");
        }

        String content = contentValue(contentNode);
        if (content.isBlank()) {
            throw new SourceItemExtractionException(source.id(), "json.contentPointer resolved to blank content");
        }
        String contentType = contentNode.isContainerNode() ? JSON_CONTENT_TYPE : TEXT_CONTENT_TYPE;

        Optional<String> externalId = optionalScalar(
                source, candidate, settings.get(SourceExtractionSettings.JSON_EXTERNAL_ID_POINTER),
                SourceExtractionSettings.JSON_EXTERNAL_ID_POINTER);
        Optional<String> title = optionalScalar(
                source, candidate, settings.get(SourceExtractionSettings.JSON_TITLE_POINTER),
                SourceExtractionSettings.JSON_TITLE_POINTER);
        Optional<String> urlValue = optionalScalar(
                source, candidate, settings.get(SourceExtractionSettings.JSON_URL_POINTER),
                SourceExtractionSettings.JSON_URL_POINTER);
        URI itemUrl = GenericExtractionSupport.resolveItemUrl(source.id(), fetchedContent.requestedUri(), urlValue);
        Optional<String> publishedValue = optionalScalar(
                source, candidate, settings.get(SourceExtractionSettings.JSON_PUBLISHED_AT_POINTER),
                SourceExtractionSettings.JSON_PUBLISHED_AT_POINTER);
        var publishedAt = GenericExtractionSupport.parseOptionalInstant(source.id(), publishedValue);

        return new ExtractedSourceItem(
                source.id(),
                itemUrl,
                GenericExtractionSupport.nonBlank(externalId),
                GenericExtractionSupport.nonBlank(title),
                content,
                contentType,
                publishedAt,
                fetchedContent.fetchedAt(),
                GenericExtractionSupport.identityPayload(externalId, title, itemUrl, content, publishedAt));
    }

    private static String contentValue(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue().strip();
        }
        if (node.isValueNode()) {
            return node.asText().strip();
        }
        return canonicalize(node).toString();
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var object = OBJECT_MAPPER.createObjectNode();
            var fieldNames = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(String::compareTo);
            for (String fieldName : fieldNames) {
                object.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return object;
        }
        if (node.isArray()) {
            var array = OBJECT_MAPPER.createArrayNode();
            node.elements().forEachRemaining(element -> array.add(canonicalize(element)));
            return array;
        }
        return node;
    }

    private static Optional<String> optionalScalar(
            ConfiguredSource source,
            JsonNode candidate,
            String pointer,
            String settingName) {
        if (pointer == null) {
            return Optional.empty();
        }
        JsonNode node = at(source, candidate, pointer, settingName);
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isValueNode()) {
            throw new SourceItemExtractionException(
                    source.id(), settingName + " must resolve to a scalar JSON value");
        }
        return Optional.of(node.asText());
    }

    private static JsonNode at(ConfiguredSource source, JsonNode node, String pointer, String settingName) {
        String value = pointer == null ? "" : pointer;
        if (!value.isEmpty() && !value.startsWith("/")) {
            throw new SourceItemExtractionException(
                    source.id(), settingName + " must be an RFC 6901 JSON Pointer starting with '/' or be empty");
        }
        try {
            return node.at(value);
        } catch (IllegalArgumentException failure) {
            throw new SourceItemExtractionException(source.id(), settingName + " is not a valid JSON Pointer", failure);
        }
    }
}
