package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The Android helper app's link: a one-time code from the website, an upload-only token, Health Connect buckets
 * through /v1/phone/ingest, and unlinking.
 */
class PhoneLinkTest extends ApiTest {

    @Override
    protected String user() {
        return afternoonUser();
    }

    Res phone(String token, String method, String path, Object body) {
        var req = switch (method) {
            case "GET" -> MockMvcRequestBuilders.get(path);
            default -> MockMvcRequestBuilders.post(path);
        };
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Phone " + token);
        }
        return call(req, null, body);
    }

    String link(String t) {
        Res code = post(t, "/v1/me/phones/code", Map.of());
        assertThat(code.status()).isEqualTo(201);
        String c = code.at("/code").asString();
        assertThat(c).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        Res linked = phone(
                null,
                "POST",
                "/v1/phone/link",
                Map.of("code", c.toLowerCase().replace("-", " "), "device_name", "Pixel 9"));
        assertThat(linked.status()).as(linked.text()).isEqualTo(200);
        return linked.at("/token").asString();
    }

    static Map<String, Object> batch(String platform) {
        String nine = LocalDate.now(AFTERNOON)
                .atTime(9, 0)
                .atZone(AFTERNOON)
                .toOffsetDateTime()
                .toString();
        return Map.of(
                "idempotency_key", UUID.randomUUID().toString(),
                "platform", platform,
                "devices",
                        List.of(Map.of(
                                "external_id",
                                "hc-pixel",
                                "name",
                                "Pixel 9",
                                "kind",
                                "phone",
                                "metrics",
                                List.of("steps"))),
                "buckets", List.of(Map.of("metric", "steps", "start", nine, "value", 321, "samples", 1)));
    }

    @Test
    void aCodeLinksAPhoneThatCanOnlyUpload() {
        String t = user();
        String token = link(t);

        Res status = phone(token, "GET", "/v1/phone/status", null);
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.at("/email").asString()).contains("@example.com");

        Res up = phone(token, "POST", "/v1/phone/ingest", batch("health_connect"));
        assertThat(up.status()).as(up.text()).isEqualTo(202);
        assertThat(up.at("/inserted").asInt()).isEqualTo(1);
        assertThat(get(t, "/v1/me/health/series/steps?days=1&interval=day")
                        .at("/points/0/value")
                        .asDouble())
                .isEqualTo(321.0);
        assertThat(phone(token, "POST", "/v1/phone/ingest", batch("healthkit")).status())
                .isEqualTo(422);

        // The phone token isn't an account token: it can't read anything.
        Res read = call(
                MockMvcRequestBuilders.get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Phone " + token), null, null);
        assertThat(read.status()).isEqualTo(401);

        Res phones = get(t, "/v1/me/phones");
        assertThat(phones.at("/0/name").asString()).isEqualTo("Pixel 9");
        assertThat(phones.at("/0/last_upload_at").isNull()).isFalse();
        assertThat(get(t, "/v1/me/export").text()).contains("phone_links").doesNotContain("token_hash");

        // Unlinking voids the token; the readings stay.
        assertThat(delete(t, "/v1/me/phones/" + phones.at("/0/id").asString()).status())
                .isEqualTo(204);
        assertThat(phone(token, "POST", "/v1/phone/ingest", batch("health_connect"))
                        .status())
                .isEqualTo(401);
        assertThat(get(t, "/v1/me/health/series/steps?days=1&interval=day")
                        .at("/points/0/value")
                        .asDouble())
                .isEqualTo(321.0);
    }

    @Test
    void aCodeWorksOnceAndANewCodeReplacesTheOld() {
        String t = user();
        String first = post(t, "/v1/me/phones/code", Map.of()).at("/code").asString();
        String second = post(t, "/v1/me/phones/code", Map.of()).at("/code").asString();
        assertThat(phone(null, "POST", "/v1/phone/link", Map.of("code", first)).code())
                .isEqualTo("invalid_code");
        assertThat(phone(null, "POST", "/v1/phone/link", Map.of("code", second)).status())
                .isEqualTo(200);
        assertThat(phone(null, "POST", "/v1/phone/link", Map.of("code", second)).code())
                .isEqualTo("invalid_code");
        assertThat(get(t, "/v1/me/phones").at("/0/name").asString()).isEqualTo("Android phone");
    }

    @Test
    void disconnectingThePhonesDeviceCardUnlinksIt() {
        String t = user();
        String token = link(t);
        assertThat(phone(token, "POST", "/v1/phone/ingest", batch("health_connect"))
                        .status())
                .isEqualTo(202);
        String id = null;
        for (var d : get(t, "/v1/me/devices").body()) {
            if (d.get("name").asString().equals("Pixel 9")) {
                id = d.get("id").asString();
            }
        }
        assertThat(id).isNotNull();
        assertThat(delete(t, "/v1/me/devices/" + id).status()).isEqualTo(204);
        assertThat(get(t, "/v1/me/phones").body().size()).isZero();
        assertThat(phone(token, "GET", "/v1/phone/status", null).status()).isEqualTo(401);
    }

    @Test
    void unknownTokensAndOtherPeoplesPhonesAreRefused() {
        assertThat(phone("not-a-token", "GET", "/v1/phone/status", null).status())
                .isEqualTo(401);
        assertThat(phone(null, "GET", "/v1/phone/status", null).status()).isEqualTo(401);
        String alice = user();
        link(alice);
        String id = get(alice, "/v1/me/phones").at("/0/id").asString();
        String bob = user();
        assertThat(delete(bob, "/v1/me/phones/" + id).status()).isEqualTo(404);
    }
}
