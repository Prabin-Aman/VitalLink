package com.vitallink.api.core.storage;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Private file storage for record documents (docs/07 §7.4 upload flow). Clients never get a permanent
 * link: uploads and downloads go through short-lived signed URLs, and the server checks what was stored
 * before a record counts it as attached.
 *
 * <p>Production: Supabase Storage, one private bucket ({@link SupabaseStorage}). Local and tests: files on
 * disk behind signed URLs served by this API ({@link LocalStorage}).
 */
public interface FileStorage {

    /** How long signed URLs live. */
    Duration TTL = Duration.ofMinutes(5);

    /** Where and how the client sends the file. */
    record SignedUpload(String url, String method, Map<String, String> headers, OffsetDateTime expiresAt) {}

    /** What was actually stored. */
    record StoredObject(long size, String contentType) {}

    SignedUpload signUpload(String key, String contentType);

    Optional<StoredObject> stat(String key);

    String signDownload(String key, String fileName);

    /** Deleting a missing object is not an error. */
    void delete(String key);
}
