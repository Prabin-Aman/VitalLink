package com.vitallink.api.account;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.storage.FileStorage;
import com.vitallink.api.core.userdata.UserData;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The profile photo, stored like record files (docs/07 §7.4): the client asks for a signed upload, PUTs the
 * image, then confirms; only a JPEG, PNG or WebP of at most 2 MB under the caller's own prefix is kept.
 * A replaced or removed photo is queued in {@code file_deletions} and deleted after the commit (the nightly
 * job retries any that fail).
 */
@Service
public class AvatarService implements UserData {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final long MAX_BYTES = 2L * 1024 * 1024;

    public record UploadIn(String contentType, long size) {}

    public record UploadOut(String key, String uploadUrl, String method, Map<String, String> headers) {}

    private final JdbcClient jdbc;
    private final FileStorage storage;

    public AvatarService(JdbcClient jdbc, FileStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    private static String prefix(UUID userId) {
        return "avatars/" + userId + "/";
    }

    public UploadOut startUpload(CurrentUser me, UploadIn body) {
        if (body.contentType() == null || !TYPES.contains(body.contentType())) {
            throw new ApiError(415, "unsupported_file_type", "Choose a JPEG, PNG or WebP image.", TYPES);
        }
        if (body.size() < 1 || body.size() > MAX_BYTES) {
            throw new ApiError(413, "file_too_large", "Photos can be up to 2 MB.");
        }
        String key = prefix(me.id()) + Ids.uuid7();
        FileStorage.SignedUpload s = storage.signUpload(key, body.contentType());
        return new UploadOut(key, s.url(), s.method(), s.headers());
    }

    /** Checks the stored image and makes it the photo; the previous one goes. */
    public void confirm(CurrentUser me, String key) {
        if (key == null
                || !key.startsWith(prefix(me.id()))
                || key.length() != prefix(me.id()).length() + 36) {
            throw ApiError.invalid("That isn't one of your uploads.");
        }
        var stored = storage.stat(key)
                .orElseThrow(() ->
                        new ApiError(409, "file_missing", "The photo hasn't arrived yet. Upload it, then confirm."));
        if (stored.size() < 1 || stored.size() > MAX_BYTES || !TYPES.contains(stored.contentType())) {
            storage.delete(key);
            throw new ApiError(422, "file_rejected", "The photo must be a JPEG, PNG or WebP image of at most 2 MB.");
        }
        String old = current(me.id());
        jdbc.sql("UPDATE vitallink.profiles SET avatar_key = :k, updated_at = now() WHERE user_id = :u")
                .param("k", key)
                .param("u", me.id())
                .update();
        if (old != null && !old.equals(key)) {
            deleteAfterCommit(old);
        }
    }

    public void remove(CurrentUser me) {
        String old = current(me.id());
        if (old == null) {
            return;
        }
        jdbc.sql("UPDATE vitallink.profiles SET avatar_key = NULL, updated_at = now() WHERE user_id = :u")
                .param("u", me.id())
                .update();
        deleteAfterCommit(old);
    }

    /** A short-lived link to the photo, or null when there is none. */
    public String url(UUID userId) {
        String key = current(userId);
        return key == null ? null : storage.signDownload(key, "photo");
    }

    private String current(UUID userId) {
        return jdbc.sql("SELECT avatar_key FROM vitallink.profiles WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void deleteAfterCommit(String key) {
        jdbc.sql("INSERT INTO vitallink.file_deletions (key) VALUES (:k) ON CONFLICT DO NOTHING")
                .param("k", key)
                .update();
        Runnable work = () -> {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.error("couldn't delete stored photo {}; the nightly job retries it", key, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    // ---------------------------------------------------------------- data rights

    /** The key is already in the profiles export; nothing extra to export. */
    @Override
    public Map<String, String> exportQueries() {
        return Map.of();
    }

    @Override
    public void beforeDelete(UUID userId) {
        String key = current(userId);
        if (key != null) {
            deleteAfterCommit(key);
        }
    }
}
