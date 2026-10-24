package com.vitallink.api.core.storage;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks the storage: Supabase when SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are set, else local files
 * (refused in production, where documents must not live on Render's temporary disk).
 */
@Configuration
public class StorageConfig {

    @Bean
    FileStorage fileStorage(
            Settings settings,
            ObjectMapper json,
            @Value("${SUPABASE_URL:}") String supabaseUrl,
            @Value("${SUPABASE_SERVICE_ROLE_KEY:}") String serviceKey,
            @Value("${STORAGE_BUCKET:records}") String bucket,
            @Value("${STORAGE_DIR:.storage}") String dir,
            @Value("${PUBLIC_API_URL:http://localhost:${PORT:8000}}") String publicUrl) {
        if (!supabaseUrl.isBlank() && !serviceKey.isBlank()) {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            return new SupabaseStorage(supabaseUrl, bucket, serviceKey, json, http);
        }
        if (!settings.isDev()) {
            throw new IllegalStateException(
                    "Production needs SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY for file storage.");
        }
        String secret =
                settings.devJwtSecret() == null || settings.devJwtSecret().isBlank()
                        ? "local-storage-only-" + "x".repeat(32)
                        : settings.devJwtSecret();
        return new LocalStorage(Path.of(dir), publicUrl, secret);
    }

    /** Serves the local store's signed URLs. Exists only when local storage is in use. */
    @RestController
    static class LocalStorageController {

        private final FileStorage storage;

        LocalStorageController(FileStorage storage) {
            this.storage = storage;
        }

        private LocalStorage local() {
            if (!(storage instanceof LocalStorage l)) {
                throw ApiError.notFound("Not found.");
            }
            return l;
        }

        @PutMapping("/local-storage/{token}")
        public ResponseEntity<Void> upload(
                @PathVariable String token,
                @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                jakarta.servlet.http.HttpServletRequest request)
                throws IOException {
            LocalStorage l = local();
            LocalStorage.Grant g = l.verify(token);
            if (!g.method().equals("PUT")) {
                throw new ApiError(403, "bad_signature", "This link isn't valid.");
            }
            l.write(g.key(), contentType, request.getInputStream());
            return ResponseEntity.ok().build();
        }

        @GetMapping("/local-storage/{token}")
        public ResponseEntity<byte[]> download(@PathVariable String token) throws IOException {
            LocalStorage l = local();
            LocalStorage.Grant g = l.verify(token);
            if (!g.method().equals("GET")) {
                throw new ApiError(403, "bad_signature", "This link isn't valid.");
            }
            Path file = l.file(g.key()).orElseThrow(() -> ApiError.notFound("Not found."));
            String type =
                    l.stat(g.key()).map(FileStorage.StoredObject::contentType).orElse("application/octet-stream");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(type))
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\""
                                    + (g.fileName() == null
                                            ? "file"
                                            : g.fileName().replace("\"", "")) + "\"")
                    .body(Files.readAllBytes(file));
        }
    }
}
