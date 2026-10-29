package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.records.RecordService;
import com.vitallink.api.support.ApiTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Records with stored files (signed upload → confirm → download) and typed-in lab panels. */
class RecordsTest extends ApiTest {

    static final byte[] PDF = "%PDF-1.7\n% a tiny test document\n".getBytes(StandardCharsets.US_ASCII);

    Res record(String u, String title, String type, String date) {
        return post(
                u, "/v1/me/records", Map.of("title", title, "type", type, "source", "Oak Cliff Imaging", "date", date));
    }

    /** PUTs bytes to a signed local-storage URL, as a browser would. */
    int putFile(String url, byte[] body, String contentType) throws Exception {
        String path = URI.create(url).getPath();
        return mvc.perform(MockMvcRequestBuilders.put(path).content(body).header(HttpHeaders.CONTENT_TYPE, contentType))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    static Map<String, Object> result(String name, double value, double low, double high, String interpretation) {
        Map<String, Object> r =
                new HashMap<>(Map.of("name", name, "value", value, "unit", "g/dL", "low", low, "high", high));
        if (interpretation != null) {
            r.put("interpretation", interpretation);
        }
        return r;
    }

    Res lab(String u, OffsetDateTime collected, List<Map<String, Object>> results) {
        return post(
                u,
                "/v1/me/labs",
                Map.of(
                        "name", "Complete Blood Count",
                        "short_name", "CBC",
                        "facility", "Trinity Valley Medical Center",
                        "laboratory", "Trinity Valley Laboratory",
                        "ordered_by", "Dr. Laura Bennett",
                        "collected_at", collected.toString(),
                        "results", results));
    }

    // ---------------------------------------------------------------- records and files

    @Test
    void uploadConfirmDownload() throws Exception {
        String u = user();
        String id = record(u, "Chest X-ray", "imaging", "2026-08-18").at("/id").asString();
        Res upload = post(
                u,
                "/v1/me/records/" + id + "/upload",
                Map.of("file_name", "chest-xray.pdf", "content_type", "application/pdf", "size", PDF.length));
        assertThat(upload.status()).as(upload.text()).isEqualTo(200);
        assertThat(upload.at("/method").asString()).isEqualTo("PUT");
        assertThat(get(u, "/v1/me/records/" + id).at("/file/status").asString()).isEqualTo("pending");

        // Confirming before the file arrives is refused.
        assertThat(post(u, "/v1/me/records/" + id + "/confirm", null).code()).isEqualTo("file_missing");

        assertThat(putFile(upload.at("/upload_url").asString(), PDF, "application/pdf"))
                .isEqualTo(200);
        Res confirmed = post(u, "/v1/me/records/" + id + "/confirm", null);
        assertThat(confirmed.status()).as(confirmed.text()).isEqualTo(200);
        assertThat(confirmed.at("/file/status").asString()).isEqualTo("attached");
        assertThat(confirmed.at("/file/size_kb").asInt()).isEqualTo(1);
        assertThat(confirmed.at("/file/content_type").asString()).isEqualTo("application/pdf");

        Res download = get(u, "/v1/me/records/" + id + "/download");
        String path = URI.create(download.at("/url").asString()).getPath();
        var file = mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse();
        assertThat(file.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("chest-xray.pdf");

        // A token can't be reused for another method or tampered with.
        assertThat(putFile(download.at("/url").asString(), PDF, "application/pdf"))
                .isEqualTo(403);
        assertThat(mvc.perform(MockMvcRequestBuilders.get(path + "x"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        // Someone else can't download it.
        assertThat(get(user(), "/v1/me/records/" + id + "/download").status()).isEqualTo(404);
        assertThat(delete(u, "/v1/me/records/" + id).status()).isEqualTo(204);
        assertThat(mvc.perform(MockMvcRequestBuilders.get(path))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void storedFilesAreCheckedOnConfirm() throws Exception {
        String u = user();
        String id = record(u, "Scan", "upload", "2026-09-01").at("/id").asString();
        Res upload = post(
                u,
                "/v1/me/records/" + id + "/upload",
                Map.of("file_name", "scan.pdf", "content_type", "application/pdf", "size", 10));
        putFile(upload.at("/upload_url").asString(), "not really a pdf".getBytes(), "text/plain");
        Res res = post(u, "/v1/me/records/" + id + "/confirm", null);
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("file_rejected");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"file_name\": \"a.exe\", \"content_type\": \"application/x-msdownload\", \"size\": 10}",
                "{\"file_name\": \"big.pdf\", \"content_type\": \"application/pdf\", \"size\": 26214401}",
                "{\"file_name\": \"\", \"content_type\": \"application/pdf\", \"size\": 10}"
            })
    void uploadValidation(String body) {
        String u = user();
        String id = record(u, "Scan", "upload", "2026-09-01").at("/id").asString();
        assertThat(post(u, "/v1/me/records/" + id + "/upload", body).status()).isIn(413, 415, 422);
    }

    @Test
    void overviewCountsEveryType() {
        String u = user();
        record(u, "Chest X-ray", "imaging", "2026-08-18");
        record(u, "Annual physical summary", "visit", "2026-08-04");
        record(u, "Influenza vaccination", "vaccination", "2025-10-12");
        Res o = get(u, "/v1/me/records/overview");
        assertThat(o.at("/counts").size()).isEqualTo(8);
        assertThat(o.at("/counts/imaging").asInt()).isEqualTo(1);
        assertThat(o.at("/counts/lab").asInt()).isZero();
        assertThat(o.at("/recent/0/title").asString()).isEqualTo("Chest X-ray");
        assertThat(o.at("/recent/0/shared_with").isNull()).isTrue();
        assertThat(o.at("/shares").size()).isZero();
        assertThat(get(u, "/v1/me/records?type=visit").body().size()).isEqualTo(1);
        assertThat(patch(u, "/v1/me/records/" + o.at("/recent/0/id").asString(), Map.of("title", "Chest X-ray (PA)"))
                        .at("/title")
                        .asString())
                .isEqualTo("Chest X-ray (PA)");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"title\": \"x\", \"type\": \"blood_bank\", \"date\": \"2026-01-01\"}",
                "{\"title\": \"\", \"type\": \"visit\", \"date\": \"2026-01-01\"}",
                "{\"title\": \"x\", \"type\": \"visit\"}",
                "{\"title\": \"x\", \"type\": \"visit\", \"date\": \"2026-01-01\", \"file_key\": \"a/b\"}"
            })
    void recordValidation(String body) {
        assertThat(post(user(), "/v1/me/records", body).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- labs

    @Test
    void labPanelsWithPreviousValuesAndInterpretation() {
        String u = user();
        OffsetDateTime earlier = OffsetDateTime.of(2026, 6, 2, 8, 30, 0, 0, ZoneOffset.ofHours(-5));
        OffsetDateTime later = OffsetDateTime.of(2026, 9, 11, 8, 30, 0, 0, ZoneOffset.ofHours(-5));
        assertThat(lab(
                                u,
                                earlier,
                                List.of(
                                        result("Hemoglobin", 14.0, 13, 17, null),
                                        result("Ferritin", 34, 30, 400, null)))
                        .status())
                .isEqualTo(201);
        Res created = lab(
                u,
                later,
                List.of(
                        result("Hemoglobin", 14.2, 13, 17, null),
                        result("Ferritin", 28, 30, 400, null), // just under: borderline
                        result("Neutrophils", 78, 40, 75, "abnormal"), // the lab's reading wins
                        result("Platelets", 100, 150, 400, null))); // well under: abnormal
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        Map<String, JsonNode> results = new HashMap<>();
        created.at("/results").forEach(r -> results.put(r.get("name").asString(), r));
        assertThat(results.get("Hemoglobin").get("previous").asDouble()).isEqualTo(14.0);
        assertThat(results.get("Hemoglobin").get("interpretation").asString()).isEqualTo("normal");
        assertThat(results.get("Ferritin").get("interpretation").asString()).isEqualTo("borderline");
        assertThat(results.get("Neutrophils").get("interpretation").asString()).isEqualTo("abnormal");
        assertThat(results.get("Neutrophils").get("previous").isNull()).isTrue();
        assertThat(results.get("Platelets").get("interpretation").asString()).isEqualTo("abnormal");
        assertThat(created.at("/report_results").size()).isEqualTo(4);

        Res labs = get(u, "/v1/me/labs");
        assertThat(labs.at("/0/collected_at").asString()).startsWith("2026-09-11");
        Res latest = get(u, "/v1/me/labs/latest-result");
        assertThat(latest.at("/panel").asString()).isEqualTo("Complete Blood Count");
        assertThat(latest.at("/result/name").asString()).isEqualTo("Hemoglobin");
        assertThat(latest.at("/result/value").asDouble()).isEqualTo(14.2);

        // Each panel shows up as a lab record, linked both ways.
        Res o = get(u, "/v1/me/records/overview");
        assertThat(o.at("/counts/lab").asInt()).isEqualTo(2);
        assertThat(o.at("/recent/0/panel_id").asString())
                .isEqualTo(created.at("/id").asString());
        assertThat(created.at("/record_id").asString())
                .isEqualTo(o.at("/recent/0/id").asString());
    }

    @Test
    void bloodGroupResultsAreRefused() {
        String u = user();
        Res res = lab(u, OffsetDateTime.now(), List.of(result("ABO blood group", 1, 0, 2, null)));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("blood_group_not_supported");
        assertThat(RecordService.BLOOD_GROUP.matcher("Rh(D) type").find()).isTrue();
        assertThat(RecordService.BLOOD_GROUP.matcher("Red Blood Cells").find()).isFalse();
        assertThat(RecordService.BLOOD_GROUP.matcher("Hemoglobin").find()).isFalse();
    }

    @Test
    void labValidationAndPrivacy() {
        String u = user();
        assertThat(lab(u, OffsetDateTime.now(), List.of(result("Hemoglobin", 14, 17, 13, null)))
                        .status())
                .isEqualTo(422);
        assertThat(lab(
                                u,
                                OffsetDateTime.now(),
                                List.of(result("Hemoglobin", 14, 13, 17, null), result("hemoglobin", 14, 13, 17, null)))
                        .status())
                .isEqualTo(422);
        assertThat(lab(u, OffsetDateTime.now(), List.of()).status()).isEqualTo(422);
        assertThat(get(u, "/v1/me/labs/latest-result").code()).isEqualTo("no_labs");
        String id = lab(u, OffsetDateTime.now().minusDays(1), List.of(result("Hemoglobin", 14, 13, 17, null)))
                .at("/id")
                .asString();
        String other = user();
        assertThat(get(other, "/v1/me/labs/" + id).status()).isEqualTo(404);
        assertThat(delete(other, "/v1/me/labs/" + id).status()).isEqualTo(404);
        assertThat(delete(u, "/v1/me/labs/" + id).status()).isEqualTo(204);
    }

    @Test
    void sharedRecordsNeedTheRecordsScope() {
        String ownerEmail = "o-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String owner = tokenFor(ownerEmail);
        String ownerId = get(owner, "/v1/me").at("/id").asString();
        record(owner, "Chest X-ray", "imaging", "2026-08-18");
        String viewerEmail = "v-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String viewer = tokenFor(viewerEmail);
        get(viewer, "/v1/me");
        assertThat(get(viewer, "/v1/users/" + ownerId + "/records").status()).isEqualTo(404);
        post(
                owner,
                "/v1/me/shares",
                Map.of(
                        "email",
                        viewerEmail,
                        "label",
                        "Dr. Bennett",
                        "kind",
                        "doctor",
                        "scopes",
                        List.of("records", "vitals")));
        Res shared = get(viewer, "/v1/users/" + ownerId + "/records");
        assertThat(shared.at("/0/title").asString()).isEqualTo("Chest X-ray");
        assertThat(shared.at("/0/shared_with").asString()).isEqualTo("Dr. Bennett");
        Res o = get(owner, "/v1/me/records/overview");
        assertThat(o.at("/shares/0/grantee").asString()).isEqualTo("Dr. Bennett");
        assertThat(o.at("/shares/0/records").asInt()).isEqualTo(1);
        // The overview names the share, so Revoke on the Records page can stop it.
        String shareId = o.at("/shares/0/id").asString();
        assertThat(get(owner, "/v1/me/shares").at("/0/id").asString()).isEqualTo(shareId);
        List<String> names = new ArrayList<>();
        get(viewer, "/v1/users/" + ownerId + "/labs")
                .body()
                .forEach(p -> names.add(p.get("name").asString()));
        assertThat(names).isEmpty();
    }
}
