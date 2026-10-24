package com.vitallink.api.core.storage;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Files on local disk behind signed URLs that this API serves at {@code /local-storage/{token}} (local
 * runs and tests only). A token names one key, one method and an expiry, signed with HMAC-SHA256, so it
 * behaves like a storage provider's signed URL.
 */
public class LocalStorage implements FileStorage {

    public static final long MAX_BYTES = 25L * 1024 * 1024;

    private final Path root;
    private final String publicUrl;
    private final byte[] secret;

    public LocalStorage(Path root, String publicUrl, String secret) {
        this.root = root.toAbsolutePath().normalize();
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** What a valid token allows. */
    public record Grant(String key, String method, String fileName) {}

    private Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || key.contains("..")) {
            throw new IllegalArgumentException("bad storage key");
        }
        return p;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    String token(String key, String method, String fileName, OffsetDateTime expires) {
        String payload = String.join(
                "\n", key, method, fileName == null ? "" : fileName, Long.toString(expires.toEpochSecond()));
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(mac(payload));
    }

    /** The grant in a token, or 403 when it's forged, tampered with or expired. */
    public Grant verify(String token) {
        int dot = token.indexOf('.');
        if (dot < 0) {
            throw new ApiError(403, "bad_signature", "This link isn't valid.");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, mac(payload))) {
                throw new ApiError(403, "bad_signature", "This link isn't valid.");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiError(403, "bad_signature", "This link isn't valid.");
        }
        String[] parts = payload.split("\n", -1);
        if (Long.parseLong(parts[3]) < Clock.now().toEpochSecond()) {
            throw new ApiError(403, "link_expired", "This link has expired.");
        }
        return new Grant(parts[0], parts[1], parts[2].isEmpty() ? null : parts[2]);
    }

    @Override
    public SignedUpload signUpload(String key, String contentType) {
        OffsetDateTime expires = Clock.now().plus(TTL);
        return new SignedUpload(
                publicUrl + "/local-storage/" + token(key, "PUT", null, expires),
                "PUT",
                Map.of("Content-Type", contentType),
                expires);
    }

    @Override
    public String signDownload(String key, String fileName) {
        return publicUrl + "/local-storage/"
                + token(key, "GET", fileName, Clock.now().plus(TTL));
    }

    /** Stores an upload; refuses anything over 25 MB. */
    public void write(String key, String contentType, InputStream body) {
        Path p = path(key);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = Files.createTempFile(p.getParent(), "upload", ".part");
            long n;
            try (InputStream limited = body) {
                n = Files.copy(new LimitedStream(limited, MAX_BYTES + 1), tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (n > MAX_BYTES) {
                Files.deleteIfExists(tmp);
                throw new ApiError(413, "file_too_large", "Files can be up to 25 MB.");
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // Only the media type counts ("application/pdf;charset=…" is stored as "application/pdf").
            String type = contentType == null
                    ? "application/octet-stream"
                    : contentType.split(";")[0].strip().toLowerCase();
            Files.writeString(type(p), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Path> file(String key) {
        Path p = path(key);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    private static Path type(Path p) {
        return p.resolveSibling(p.getFileName() + ".type");
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        Path p = path(key);
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            String type = Files.exists(type(p)) ? Files.readString(type(p)).strip() : "application/octet-stream";
            return Optional.of(new StoredObject(Files.size(p), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        Path p = path(key);
        try {
            Files.deleteIfExists(p);
            Files.deleteIfExists(type(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads at most {@code limit} bytes. */
    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
