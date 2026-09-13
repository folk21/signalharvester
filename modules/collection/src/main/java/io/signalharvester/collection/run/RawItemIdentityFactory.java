package io.signalharvester.collection.run;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives deterministic raw-item identities from source provenance, item URL, and identity payload.
 *
 * <p>Passthrough REST/HTML extraction supplies the original response bytes as identity material, so
 * existing one-response-per-source identities stay stable. RSS/Atom extraction supplies canonical
 * per-entry identity material, allowing one fetched feed document to produce multiple stable raw ids.</p>
 */
@Singleton
public final class RawItemIdentityFactory {

    private static final byte[] SEPARATOR = new byte[] {0};

    /** Returns the same identity for the same extracted source item across collection runs. */
    public String identityFor(ExtractedSourceItem item) {
        Objects.requireNonNull(item, "item");
        return identity(item.sourceId().value().getMostSignificantBits(),
                item.sourceId().value().getLeastSignificantBits(),
                item.url().toASCIIString(),
                item.identityPayload());
    }

    /**
     * Retains the original fetch-payload identity contract for focused transport tests and compatibility.
     */
    public String identityFor(FetchedSourceContent content) {
        Objects.requireNonNull(content, "content");
        return identity(content.sourceId().value().getMostSignificantBits(),
                content.sourceId().value().getLeastSignificantBits(),
                content.requestedUri().toASCIIString(),
                content.body());
    }

    private static String identity(long mostSignificantBits, long leastSignificantBits, String url, byte[] payload) {
        MessageDigest digest = sha256();
        digest.update(uuidBytes(mostSignificantBits, leastSignificantBits));
        digest.update(SEPARATOR);
        digest.update(url.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        digest.update(payload);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static byte[] uuidBytes(long mostSignificantBits, long leastSignificantBits) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(mostSignificantBits)
                .putLong(leastSignificantBits)
                .array();
    }
}
