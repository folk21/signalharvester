package io.signalharvester.analysis.normalization;

import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Derives a stable logical content identity from explicit external ids or normalized fingerprints.
 *
 * <p>The identity is intentionally profile-independent. Deduplication scope is applied separately by
 * monitoring profile so one external item may be processed independently for multiple profiles.</p>
 */
@Singleton
public final class NormalizedItemIdentityFactory {

    private static final byte[] SEPARATOR = new byte[] {0};

    /**
     * Uses source + external id when available, otherwise source + URL + normalized content.
     */
    public String identityFor(
            String sourceId,
            Optional<String> externalId,
            URI normalizedUrl,
            String normalizedContent) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(normalizedUrl, "normalizedUrl");
        Objects.requireNonNull(normalizedContent, "normalizedContent");

        MessageDigest digest = sha256();
        if (externalId.isPresent()) {
            update(digest, "external");
            update(digest, sourceId);
            update(digest, externalId.orElseThrow());
        } else {
            update(digest, "fingerprint");
            update(digest, sourceId);
            update(digest, normalizedUrl.toASCIIString());
            update(digest, normalizedContent);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
