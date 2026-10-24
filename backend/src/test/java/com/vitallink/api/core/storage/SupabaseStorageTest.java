package com.vitallink.api.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vitallink.api.core.security.IdentityAdmin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production storage and identity clients against a fake Supabase: the right REST calls, the
 * service-role key on each, and failures surfacing as "storage unavailable" (503) rather than as success.
 */
class SupabaseStorageTest {

    record Call(String method, String path, String auth, String body) {}

    HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getRawPath();
            calls.add(
                    new Call(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("Authorization"), body));
            String reply = "{}";
            if (path.startsWith("/storage/v1/object/upload/sign/")) {
                reply = "{\"url\": \"/object/upload/sign/records/u/r/f?token=up\"}";
            } else if (path.startsWith("/storage/v1/object/sign/")) {
                reply = "{\"signedURL\": \"/object/sign/records/u/r/f?token=down\"}";
            }
            if (ex.getRequestMethod().equals("HEAD")) {
                ex.getResponseHeaders().add("Content-Type", "application/pdf");
                ex.getResponseHeaders().add("Content-Length", "2048");
                ex.sendResponseHeaders(status, -1);
            } else {
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    SupabaseStorage storage() {
        return new SupabaseStorage(
                base, "records", "service-key", JsonMapper.builder().build(), HttpClient.newHttpClient());
    }

    @Test
    void signsUploadsAndDownloadsWithTheServiceKey() {
        var up = storage().signUpload("u/r/f", "application/pdf");
        assertThat(up.url()).isEqualTo(base + "/storage/v1/object/upload/sign/records/u/r/f?token=up");
        assertThat(up.method()).isEqualTo("PUT");
        assertThat(up.headers()).containsEntry("Content-Type", "application/pdf");

        String down = storage().signDownload("u/r/f", "CBC report.pdf");
        assertThat(down).isEqualTo(base + "/storage/v1/object/sign/records/u/r/f?token=down&download=CBC+report.pdf");
        assertThat(calls.get(1).body()).contains("\"expiresIn\": 300");
        assertThat(calls).allMatch(c -> "Bearer service-key".equals(c.auth()));
    }

    @Test
    void statReadsSizeAndTypeAndDeleteSendsThePrefix() {
        var stored = storage().stat("u/r/f").orElseThrow();
        assertThat(stored.size()).isEqualTo(2048);
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(calls.getFirst().method()).isEqualTo("HEAD");
        assertThat(calls.getFirst().path()).isEqualTo("/storage/v1/object/authenticated/records/u/r/f");

        storage().delete("u/r/f");
        assertThat(calls.get(1).method()).isEqualTo("DELETE");
        assertThat(calls.get(1).path()).isEqualTo("/storage/v1/object/records");
        assertThat(calls.get(1).body()).contains("\"prefixes\"").contains("u/r/f");
    }

    @Test
    void missingObjectsAndFailures() {
        status = 404;
        assertThat(storage().stat("u/r/missing")).isEmpty();
        storage().delete("u/r/missing"); // deleting what isn't there is fine
        status = 500;
        assertThatThrownBy(() -> storage().signUpload("u/r/f", "application/pdf"))
                .isInstanceOf(SupabaseStorage.StorageUnavailable.class);
        assertThatThrownBy(() -> storage().stat("u/r/f")).isInstanceOf(SupabaseStorage.StorageUnavailable.class);
    }

    @Test
    void identityAdminDeletesTheSubject() {
        UUID subject = UUID.randomUUID();
        assertThat(new IdentityAdmin(base, "service-key").deleteIdentity(subject))
                .isTrue();
        assertThat(calls.getFirst().method()).isEqualTo("DELETE");
        assertThat(calls.getFirst().path()).isEqualTo("/auth/v1/admin/users/" + subject);
        status = 500;
        assertThat(new IdentityAdmin(base, "service-key").deleteIdentity(subject))
                .isFalse();
        // Without configuration (local, tests) there's nothing to delete.
        assertThat(new IdentityAdmin("", "").deleteIdentity(subject)).isTrue();
    }
}
