package com.vitallink.api.support;

import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.DevTokens;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for API tests: the whole app (filters, transactions, error handler) on the test database, called
 * through MockMvc with dev tokens. Each test creates its own users, so tests never share data.
 */
@SpringBootTest(
        properties = {
            "vitallink.env=test",
            "spring.flyway.clean-disabled=true",
            "STORAGE_DIR=target/test-storage",
            "JOB_SECRET=test-job-secret"
        })
@AutoConfigureMockMvc
public abstract class ApiTest {

    public static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /**
     * A fixed-offset zone where it is about 2 PM when the tests start. Tests that write "today at 9:00"
     * use it so their buckets are never in the future, whatever time of day the suite runs.
     */
    public static final ZoneId AFTERNOON = afternoonZone();

    public static final String TEST_SECRET = "test-only-secret-" + "x".repeat(32);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected Settings settings;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var c = TestDatabase.connection();
        registry.add("DATABASE_URL", c::jdbcUrl);
        registry.add("DATABASE_USER", () -> c.user() == null ? "" : c.user());
        registry.add("DATABASE_PASSWORD", () -> c.password() == null ? "" : c.password());
        registry.add("vitallink.dev-jwt-secret", () -> TEST_SECRET);
    }

    /** A response: status and parsed JSON body (missing node when there's no body). */
    public record Res(int status, JsonNode body) {

        public JsonNode at(String pointer) {
            return body.at(pointer);
        }

        public String code() {
            return body.at("/error/code").asString();
        }

        public String text() {
            return body.toString();
        }
    }

    /** Authorization header value for a new (or given) dev account. */
    protected String token(String email, UUID subject, boolean verified, String first, String last) {
        UUID sub = subject != null ? subject : UUID.randomUUID();
        String mail = email != null ? email : "user-" + sub.toString().substring(0, 12) + "@example.com";
        return "Bearer " + DevTokens.mint(settings, sub, mail, verified, first, last, 3600, null);
    }

    protected String token() {
        return token(null, null, true, null, null);
    }

    protected String tokenFor(String email) {
        return token(email, null, true, null, null);
    }

    /** A new account that has made its first call. */
    protected String user() {
        String t = token();
        get(t, "/v1/me");
        return t;
    }

    protected Res call(MockHttpServletRequestBuilder req, String auth, Object body) {
        try {
            if (auth != null) {
                req.header(HttpHeaders.AUTHORIZATION, auth);
            }
            if (body != null) {
                req.contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : json.writeValueAsString(body));
            }
            var r = mvc.perform(req).andReturn().getResponse();
            String content = r.getContentAsString();
            JsonNode node = content.isEmpty() ? json.missingNode() : json.readTree(content);
            return new Res(r.getStatus(), node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res get(String auth, String path) {
        return call(MockMvcRequestBuilders.get(path), auth, null);
    }

    protected Res post(String auth, String path, Object body) {
        return call(MockMvcRequestBuilders.post(path), auth, body);
    }

    protected Res put(String auth, String path, Object body) {
        return call(MockMvcRequestBuilders.put(path), auth, body);
    }

    protected Res patch(String auth, String path, Object body) {
        return call(MockMvcRequestBuilders.patch(path), auth, body);
    }

    protected Res delete(String auth, String path) {
        return call(MockMvcRequestBuilders.delete(path), auth, null);
    }

    /** Runs SQL directly on the test database (checks below the API). */
    protected java.sql.Connection sql() {
        try {
            return TestDatabase.open();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ZoneId afternoonZone() {
        int offset = 14 - ZonedDateTime.now(ZoneOffset.UTC).getHour();
        if (offset < -12) offset += 24;
        // Etc/GMT names are sign-inverted: Etc/GMT-5 is UTC+5.
        return ZoneId.of(offset == 0 ? "Etc/GMT" : "Etc/GMT" + (offset > 0 ? "-" : "+") + Math.abs(offset));
    }

    /** A new account whose time zone is {@link #AFTERNOON}. */
    protected String afternoonUser() {
        String t = token();
        get(t, "/v1/me");
        patch(t, "/v1/me/account", java.util.Map.of("timezone", AFTERNOON.getId()));
        return t;
    }

    protected static ZonedDateTime todayAt(ZoneId zone, int hour, int minute, int daysAgo) {
        LocalDate day = LocalDate.now(zone).minusDays(daysAgo);
        return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(zone);
    }

    protected static ZonedDateTime todayAt(int hour, int minute, int daysAgo) {
        LocalDate day = LocalDate.now(CHICAGO).minusDays(daysAgo);
        return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(CHICAGO);
    }

    protected static String iso(ZonedDateTime t) {
        return t.toOffsetDateTime().toString();
    }
}
