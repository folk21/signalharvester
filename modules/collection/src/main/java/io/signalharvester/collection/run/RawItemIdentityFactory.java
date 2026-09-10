package io.signalharvester.collection.run;

import io.signalharvester.collection.source.FetchedSourceContent;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives a deterministic raw-item identity from source provenance, requested URI, and raw payload.
 *
 * <p>This is idempotency groundwork for the one-payload-per-source HTTP model. Source-specific
 * extraction may later supply finer-grained external identities without changing the Kafka publisher.</p>
 */
@Singleton
public final class RawItemIdentityFactory {

    private static final byte[] SEPARATOR = new byte[] {0};

    /**
     * Returns the same identity for the same source, URI, and payload across collection runs.
     *
     * @param content fetched raw source content
     * @return lowercase SHA-256 identity
     */
    public String identityFor(FetchedSourceContent content) {
        Objects.requireNonNull(content, "content");
        MessageDigest digest = sha256();
        digest.update(uuidBytes(content.sourceId().value().getMostSignificantBits(), content.sourceId().value().getLeastSignificantBits()));
        digest.update(SEPARATOR);
        digest.update(content.requestedUri().toASCIIString().getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        digest.update(content.body());
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
