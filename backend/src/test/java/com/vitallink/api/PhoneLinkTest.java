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
import tools.jackson.databind.JsonNode;

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

    Res shortcutUpload(String token, String body) {
        var req = MockMvcRequestBuilders.post("/v1/phone/import")
                .header(HttpHeaders.AUTHORIZATION, "Phone " + token)
                .contentType("text/plain")
                .content(body);
        return call(req, null, null);
    }

    @Test
    void theIphoneShortcutSendsReadingsAsAppleHealth() {
        String t = user();
        Res made = post(t, "/v1/me/phones/shortcut", Map.of());
        assertThat(made.status()).isEqualTo(201);
        String token = made.at("/token").asString();

        var day = LocalDate.now(AFTERNOON);
        String nine = day.atTime(9, 0).atZone(AFTERNOON).toOffsetDateTime().toString();
        String nineTen = day.atTime(9, 10).atZone(AFTERNOON).toOffsetDateTime().toString();
        String one = day.atTime(1, 0).atZone(AFTERNOON).toOffsetDateTime().toString();
        String oneThirty =
                day.atTime(1, 30).atZone(AFTERNOON).toOffsetDateTime().toString();
        String old = day.minusDays(3)
                .atTime(9, 0)
                .atZone(AFTERNOON)
                .toOffsetDateTime()
                .toString();
        String body = String.join(
                "\n",
                "start|metric|value|end|unit|source",
                // The iPhone and the Watch count the same walk: one source per bucket, not both.
                nine + "|steps|600|" + nineTen + "|count|Abhi's iPhone",
                nine + "|steps|500|" + nineTen + "|count|Abhi's Apple Watch",
                nine + "|heart_rate|72||count/min|Abhi's Apple Watch",
                nine + "|weight|154.32||lb|Health",
                nine + "|distance|0.5|" + nineTen + "|mi|Abhi's iPhone",
                one + "|sleep|Deep|" + oneThirty + "||Abhi's Apple Watch",
                old + "|steps|999|" + old + "|count|Abhi's iPhone",
                nine + "|nonsense|1||count|x");
        Res up = shortcutUpload(token, body);
        assertThat(up.status()).as(up.text()).isEqualTo(200);
        assertThat(up.at("/skipped").asInt()).isEqualTo(1);

        assertThat(get(t, "/v1/me/health/series/steps?days=1&interval=day")
                        .at("/points/0/value")
                        .asDouble())
                .isEqualTo(600.0);
        JsonNode latest =
                get(t, "/v1/me/health/latest?metrics=weight,heart_rate").body();
        assertThat(latest.toString()).contains("\"value\":70.0").contains("\"value\":72");
        assertThat(get(t, "/v1/me/health/series/distance?days=1&interval=day")
                        .at("/points/0/value")
                        .asDouble())
                .isCloseTo(804.672, org.assertj.core.data.Offset.offset(0.1));
        // Three days ago is outside what the Shortcut may write.
        assertThat(get(t, "/v1/me/health/series/steps?days=5&interval=day").text())
                .doesNotContain("999");

        // Sending the same lines again changes nothing.
        Res again = shortcutUpload(token, body);
        assertThat(again.at("/inserted").asInt() + again.at("/updated").asInt()).isZero();

        // Its card is "iPhone (Shortcut)"; Disconnect there unlinks it.
        String id = null;
        for (var d : get(t, "/v1/me/devices").body()) {
            if (d.get("name").asString().equals("iPhone (Shortcut)")) {
                id = d.get("id").asString();
            }
        }
        assertThat(id).isNotNull();
        assertThat(delete(t, "/v1/me/devices/" + id).status()).isEqualTo(204);
        assertThat(shortcutUpload(token, body).status()).isEqualTo(401);
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
