package io.signalharvester.results.application;

import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/** Encodes and validates opaque, criteria-bound keyset cursors for Results browsing. */
@Singleton
public final class ResultCursorCodec {

    private static final int VERSION = 1;
    private static final int SHA_256_BYTES = 32;
    private static final int MAX_PROFILE_ID_BYTES = 128;

    /** Encodes the sort key of the last returned result and binds it to the supplied criteria. */
    public String encode(ResultSummary result, ResultQueryCriteria criteria) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(criteria, "criteria");
        byte[] profileId = result.monitoringProfileId().getBytes(StandardCharsets.UTF_8);
        if (profileId.length == 0 || profileId.length > MAX_PROFILE_ID_BYTES) {
            throw new InvalidResultQueryException("monitoringProfileId is invalid for a Results cursor");
        }
        if (!result.normalizedItemId().matches("[0-9a-f]{64}")) {
            throw new InvalidResultQueryException("normalizedItemId is invalid for a Results cursor");
        }

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeByte(VERSION);
                output.writeLong(result.analyzedAt().getEpochSecond());
                output.writeInt(result.analyzedAt().getNano());
                output.writeInt(profileId.length);
                output.write(profileId);
                output.write(result.normalizedItemId().getBytes(StandardCharsets.US_ASCII));
                output.write(criteriaFingerprint(criteria));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode Results cursor", exception);
        }
    }

    /** Decodes a cursor and rejects values produced for materially different browsing criteria. */
    public ResultPagePosition decode(String cursor, ResultQueryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidResultQueryException("cursor must not be blank");
        }

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw new InvalidResultQueryException("cursor is not valid URL-safe base64", exception);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new InvalidResultQueryException("Unsupported Results cursor version: " + version);
            }

            Instant analyzedAt;
            try {
                analyzedAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            } catch (DateTimeException exception) {
                throw new InvalidResultQueryException("cursor contains an invalid analyzedAt value", exception);
            }

            int profileLength = input.readInt();
            if (profileLength <= 0 || profileLength > MAX_PROFILE_ID_BYTES) {
                throw new InvalidResultQueryException("cursor contains an invalid monitoringProfileId length");
            }
            byte[] profileIdBytes = input.readNBytes(profileLength);
            if (profileIdBytes.length != profileLength) {
                throw new InvalidResultQueryException("cursor is truncated before monitoringProfileId");
            }
            String monitoringProfileId = new String(profileIdBytes, StandardCharsets.UTF_8);
            if (monitoringProfileId.isBlank()) {
                throw new InvalidResultQueryException("cursor contains a blank monitoringProfileId");
            }

            byte[] normalizedItemIdBytes = input.readNBytes(64);
            if (normalizedItemIdBytes.length != 64) {
                throw new InvalidResultQueryException("cursor is truncated before normalizedItemId");
            }
            String normalizedItemId = new String(normalizedItemIdBytes, StandardCharsets.US_ASCII);
            if (!normalizedItemId.matches("[0-9a-f]{64}")) {
                throw new InvalidResultQueryException("cursor contains an invalid normalizedItemId");
            }

            byte[] fingerprint = input.readNBytes(SHA_256_BYTES);
            if (fingerprint.length != SHA_256_BYTES || input.read() != -1) {
                throw new InvalidResultQueryException("cursor has an invalid payload length");
            }
            if (!MessageDigest.isEqual(fingerprint, criteriaFingerprint(criteria))) {
                throw new InvalidResultQueryException("cursor does not match the supplied Results filters");
            }

            return new ResultPagePosition(analyzedAt, monitoringProfileId, normalizedItemId);
        } catch (EOFException exception) {
            throw new InvalidResultQueryException("cursor payload is truncated", exception);
        } catch (IOException exception) {
            throw new InvalidResultQueryException("cursor payload cannot be decoded", exception);
        }
    }

    private static byte[] criteriaFingerprint(ResultQueryCriteria criteria) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, criteria.monitoringProfileId());
            update(digest, criteria.sourceId());
            update(digest, criteria.informationCategory());
            update(digest, criteria.relevant().map(value -> Boolean.toString(value)));
            update(digest, criteria.classification());
            update(digest, criteria.analyzedFrom().map(Instant::toString));
            update(digest, criteria.analyzedTo().map(Instant::toString));
            update(digest, criteria.search());
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Optional<String> value) {
        if (value.isEmpty()) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.orElseThrow().getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
