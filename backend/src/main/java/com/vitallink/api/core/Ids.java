package com.vitallink.api.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 ids: time-ordered (indexes stay compact), not enumerable (docs/02-architecture.md). */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** RFC 4122 URL namespace, as Python's {@code uuid.NAMESPACE_URL}. */
    public static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private Ids() {}

    /** RFC 9562 version 7: 48-bit Unix ms timestamp, then 74 random bits. */
    public static UUID uuid7() {
        long ms = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long rand = ByteBuffer.wrap(random, 0, 8).getLong();
        long high = (ms << 16) | (0x7L << 12) | ((random[8] & 0xFFL) << 4 | (random[9] & 0x0FL));
        long low = (rand & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // RFC 4122 variant
        return new UUID(high, low);
    }

    /** RFC 4122 version 5 (SHA-1 of namespace + name), byte-for-byte the same as Python's uuid5. */
    public static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
