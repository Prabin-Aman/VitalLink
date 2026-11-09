package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitallink.api.records.RecordService;
import com.vitallink.api.support.ApiTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Regression tests for the security review's findings; each failed before its fix. */
class ReviewFixesTest extends ApiTest {

    static final byte[] OLD = "%PDF-1.7\n% the original report\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] NEW = "%PDF-1.7\n% the corrected report, a little longer\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    RecordService records;

    String record(String u) {
        return post(u, "/v1/me/records", Map.of("title", "Lipid panel", "type", "lab", "date", "2026-09-01"))
                .at("/id")
                .asString();
    }

    Res startUpload(String u, String id, String name, int size) {
        Res res = post(
                u,
                "/v1/me/records/" + id + "/upload",
                Map.of("file_name", name, "content_type", "application/pdf", "size", size));
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return res;
    }

    int putFile(String url, byte[] body, String contentType) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(URI.create(url).getPath())
                        .content(body)
                        .header(HttpHeaders.CONTENT_TYPE, contentType))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** The bytes behind a record's download link, or null when there's none. */
    byte[] download(String u, String id) throws Exception {
        Res link = get(u, "/v1/me/records/" + id + "/download");
        if (link.status() != 200) {
            return null;
        }
        return mvc.perform(MockMvcRequestBuilders.get(
                        URI.create(link.at("/url").asString()).getPath()))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    String attached(String u, byte[] file) throws Exception {
        String id = record(u);
        putFile(startUpload(u, id, "lipids.pdf", file.length).at("/upload_url").asString(), file, "application/pdf");
        assertThat(post(u, "/v1/me/records/" + id + "/confirm", null).status()).isEqualTo(200);
        return id;
    }

    void exec(String statement) throws SQLException {
        try (var c = sql()) {
            c.createStatement().executeUpdate(statement);
        }
    }

    String fileKey(String id) throws SQLException {
        try (var c = sql();
                var rs = c.createStatement()
                        .executeQuery("SELECT file_key FROM vitallink.health_records WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ---------------------------------------------------------------- R5: replacing a file

    @Test
    void replacingAFileKeepsTheOldOneUntilTheNewOneIsConfirmed() throws Exception {
        String u = user();
        String id = attached(u, OLD);

        Res replacement = startUpload(u, id, "lipids-corrected.pdf", NEW.length);
        Res during = get(u, "/v1/me/records/" + id);
        assertThat(during.at("/file/status").asString()).isEqualTo("attached");
        assertThat(during.at("/file/name").asString()).isEqualTo("lipids.pdf");
        assertThat(download(u, id)).isEqualTo(OLD);

        // Nothing arrived, then something that isn't a PDF: the original is still there both times.
        assertThat(post(u, "/v1/me/records/" + id + "/confirm", null).code()).isEqualTo("file_missing");
        putFile(replacement.at("/upload_url").asString(), "not a pdf".getBytes(), "text/plain");
        assertThat(post(u, "/v1/me/records/" + id + "/confirm", null).code()).isEqualTo("file_rejected");
        assertThat(download(u, id)).isEqualTo(OLD);

        String oldKey = fileKey(id);
        Res again = startUpload(u, id, "lipids-corrected.pdf", NEW.length);
        putFile(again.at("/upload_url").asString(), NEW, "application/pdf");
        Res confirmed = post(u, "/v1/me/records/" + id + "/confirm", null);
        assertThat(confirmed.status()).as(confirmed.text()).isEqualTo(200);
        assertThat(confirmed.at("/file/name").asString()).isEqualTo("lipids-corrected.pdf");
        assertThat(download(u, id)).isEqualTo(NEW);
        assertThat(fileKey(id)).isNotEqualTo(oldKey);
    }

    @Test
    void anAbandonedReplacementIsDroppedAndTheOriginalKept() throws Exception {
        String u = user();
        String id = attached(u, OLD);
        startUpload(u, id, "never-sent.pdf", NEW.length);
        exec("UPDATE vitallink.health_records SET upload_requested_at = now() - interval '2 days' WHERE id = '" + id
                + "'");

        records.cleanAbandonedUploads();

        assertThat(get(u, "/v1/me/records/" + id).at("/file/name").asString()).isEqualTo("lipids.pdf");
        assertThat(download(u, id)).isEqualTo(OLD);
        assertThat(post(u, "/v1/me/records/" + id + "/confirm", null).code()).isEqualTo("no_pending_upload");
    }

    // ---------------------------------------------------------------- R6: cleanup racing a confirm

    @Test
    void theNightlyCleanupNeverUndoesAConfirmCommittingMeanwhile() throws Exception {
        String u = user();
        String id = record(u);
        startUpload(u, id, "late.pdf", 10);
        exec("UPDATE vitallink.health_records SET upload_requested_at = now() - interval '2 days' WHERE id = '" + id
                + "'");

        // A confirm holds the row (attached, not committed yet) while the cleanup runs.
        try (var confirm = sql()) {
            confirm.setAutoCommit(false);
            confirm.createStatement()
                    .executeUpdate("UPDATE vitallink.health_records SET file_status = 'attached', file_size = 10, "
                            + "file_type = 'application/pdf' WHERE id = '" + id + "'");
            var cleanup = CompletableFuture.supplyAsync(records::cleanAbandonedUploads);
            Thread.sleep(500);
            assertThat(cleanup).isNotDone(); // waiting for the confirm's row lock
            confirm.commit();
            cleanup.get(10, TimeUnit.SECONDS);
        }

        assertThat(get(u, "/v1/me/records/" + id).at("/file/status").asString()).isEqualTo("attached");
    }

    // ---------------------------------------------------------------- R7: files of deleted records

    @Test
    void deletedFilesStayQueuedUntilTheNightlyJobDeletesThem() throws Exception {
        String u = user();
        String id = attached(u, OLD);
        String key = fileKey(id);
        assertThat(delete(u, "/v1/me/records/" + id).status()).isEqualTo(204);

        // Queued with the delete, so a Storage outage right then can't leave the file behind for good.
        String count = "SELECT count(*) FROM vitallink.file_deletions WHERE key = '" + key + "'";
        try (var c = sql();
                var rs = c.createStatement().executeQuery(count)) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        assertThat(records.drainFileDeletions()).isPositive();
        try (var c = sql();
                var rs = c.createStatement().executeQuery(count)) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    // ---------------------------------------------------------------- R2 end to end: sign-up names

    @Test
    void longSignUpNamesAreCutToTheProfileLimit() {
        String u = token(null, null, true, "N".repeat(200), "Bennett");
        Res account = get(u, "/v1/me/account");
        assertThat(account.status()).as(account.text()).isEqualTo(200);
        assertThat(account.at("/first_name").asString()).hasSize(60);
        assertThat(account.at("/last_name").asString()).isEqualTo("Bennett");
    }

    // ---------------------------------------------------------------- S1, S2: schema

    @Test
    void aLabPanelCanOnlyPointAtItsOwnersRecord() throws SQLException {
        String a = user();
        String b = user();
        String recordOfA = record(a);
        UUID idOfB = UUID.fromString(get(b, "/v1/me").at("/id").asString());
        assertThatThrownBy(() -> exec("""
                        INSERT INTO vitallink.lab_panels (id, user_id, record_id, name, short_name, collected_at,
                          reported_at)
                        VALUES ('%s', '%s', '%s', 'Lipids', 'Lipids', now(), now())
                        """.formatted(UUID.randomUUID(), idOfB, recordOfA)))
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23503"));
    }

    @Test
    void limitsTheApiAppliesHoldInTheSchemaToo() {
        String id = get(user(), "/v1/me").at("/id").asString();
        for (String statement : new String[] {
            "UPDATE vitallink.profiles SET first_name = '" + "A".repeat(61) + "' WHERE user_id = '" + id + "'",
            "UPDATE vitallink.profiles SET language = 'xx' WHERE user_id = '" + id + "'",
            "UPDATE vitallink.users SET timezone = '' WHERE id = '" + id + "'"
        }) {
            assertThatThrownBy(() -> exec(statement))
                    .as(statement)
                    .isInstanceOfSatisfying(
                            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }
}
