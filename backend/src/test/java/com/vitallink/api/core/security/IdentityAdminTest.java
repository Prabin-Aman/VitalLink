package com.vitallink.api.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Supabase's own record decides whether an email is confirmed (review finding R1): the admin API is asked,
 * and anything but a confirmed match for the same address is "no".
 */
class IdentityAdminTest {

    HttpServer server;
    volatile int status = 200;
    volatile String reply = "{}";
    volatile String path;
    volatile String auth;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path = ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath();
            auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    boolean confirmed(String email) {
        return new IdentityAdmin(base, "service-key").emailConfirmed(UUID.fromString(SUBJECT), email);
    }

    static final String SUBJECT = "6f1c2a9e-0b7d-4c3e-9a51-2d8e4f6b7c10";

    @Test
    void confirmedOnlyWhenSupabaseSaysSoForThisAddress() {
        reply = "{\"id\": \"" + SUBJECT + "\", \"email\": \"ana@example.com\", "
                + "\"email_confirmed_at\": \"2026-10-01T12:00:00Z\"}";
        assertThat(confirmed("ana@example.com")).isTrue();
        assertThat(path).isEqualTo("GET /auth/v1/admin/users/" + SUBJECT);
        assertThat(auth).isEqualTo("Bearer service-key");
        assertThat(confirmed("someone-else@example.com")).isFalse();

        reply = "{\"email\": \"ana@example.com\", \"email_confirmed_at\": null}";
        assertThat(confirmed("ana@example.com")).isFalse();
        reply = "{\"email\": \"ana@example.com\"}";
        assertThat(confirmed("ana@example.com")).isFalse();
    }

    @Test
    void failsClosed() {
        status = 500;
        reply = "{\"email\": \"ana@example.com\", \"email_confirmed_at\": \"2026-10-01T12:00:00Z\"}";
        assertThat(confirmed("ana@example.com")).isFalse();
        status = 200;
        reply = "not json";
        assertThat(confirmed("ana@example.com")).isFalse();
        assertThat(new IdentityAdmin("", "").emailConfirmed(UUID.fromString(SUBJECT), "ana@example.com"))
                .isFalse();
    }
}
