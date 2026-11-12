package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vitallink.api.nutrition.FoodDataCentral;
import com.vitallink.api.support.ApiTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Food search with USDA FoodData Central, against a stand-in for USDA on a local port: foods found there are stored
 * once and can be logged, answers are reused, incomplete foods are left out, and an outage leaves the search working.
 */
class FoodDataCentralTest extends ApiTest {

    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile boolean down;
    static final HttpServer USDA = start();

    static final String ANSWER = """
            {"totalHits": 3, "foods": [
              {"fdcId": 173944, "description": "BANANAS, RAW", "dataType": "SR Legacy", "foodMeasures": [],
               "foodNutrients": [
                 {"nutrientNumber": "208", "unitName": "KCAL", "value": 89.0},
                 {"nutrientNumber": "203", "unitName": "G", "value": 1.09},
                 {"nutrientNumber": "204", "unitName": "G", "value": 0.33},
                 {"nutrientNumber": "205", "unitName": "G", "value": 22.8},
                 {"nutrientNumber": "291", "unitName": "G", "value": 2.6}]},
              {"fdcId": 2709224, "description": "Banana bread", "dataType": "Survey (FNDDS)",
               "foodMeasures": [{"disseminationText": "1 slice", "gramWeight": 60}],
               "foodNutrients": [
                 {"nutrientNumber": "958", "unitName": "KCAL", "value": 326},
                 {"nutrientNumber": "203", "unitName": "G", "value": 4.3},
                 {"nutrientNumber": "204", "unitName": "G", "value": 10.5},
                 {"nutrientNumber": "205", "unitName": "G", "value": 54.6}]},
              {"fdcId": 999, "description": "Banana chips, no protein listed", "foodNutrients": [
                 {"nutrientNumber": "208", "unitName": "KCAL", "value": 519}]}
            ]}
            """;

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/fdc/v1/foods/search", x -> {
                CALLS.incrementAndGet();
                boolean keyed = x.getRequestURI().getRawQuery().contains("api_key=test-key");
                byte[] b = (down || !keyed ? "{}" : ANSWER).getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(down ? 500 : keyed ? 200 : 403, b.length);
                x.getResponseBody().write(b);
                x.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void usda(DynamicPropertyRegistry r) {
        r.add("vitallink.usda-api-key", () -> "test-key");
        r.add(
                "vitallink.usda-api-url",
                () -> "http://127.0.0.1:" + USDA.getAddress().getPort() + "/fdc/v1");
    }

    @AfterAll
    static void stop() {
        USDA.stop(0);
    }

    @Test
    void usdaFoodsAreFoundStoredOnceAndLoggable() {
        String u = user();
        int before = CALLS.get();
        JsonNode hits = get(u, "/v1/foods/search?q=banana").body();
        assertThat(CALLS.get()).isEqualTo(before + 1);
        JsonNode banana = null;
        for (JsonNode h : hits) {
            if (h.get("name").asString().equals("Bananas, raw")) {
                banana = h;
            }
        }
        assertThat(banana).as(hits.toString()).isNotNull();
        assertThat(banana.get("source").asString()).isEqualTo("usda");
        assertThat(banana.get("kcal").asDouble()).isEqualTo(89.0);
        assertThat(banana.get("serving_g").asDouble()).isEqualTo(100.0);
        assertThat(hits.toString()).contains("Banana bread").doesNotContain("no protein listed");
        for (JsonNode h : hits) {
            if (h.get("name").asString().equals("Banana bread")) {
                assertThat(h.get("kcal").asDouble()).isEqualTo(326.0); // Atwater energy when 208 is missing
                assertThat(h.get("serving_g").asDouble()).isEqualTo(60.0);
            }
        }

        // The same words again: the stored food, and USDA isn't asked twice.
        JsonNode again = get(u, "/v1/foods/search?q=banana").body();
        assertThat(CALLS.get()).isEqualTo(before + 1);
        assertThat(again.toString()).contains(banana.get("id").asString());

        // Anyone else finds the same stored food, and it can be logged like VitalLink's own foods.
        String other = user();
        Res meal = post(
                other,
                "/v1/me/meals",
                Map.of(
                        "slot", "snack",
                        "eaten_at",
                                LocalDate.now()
                                        .atTime(8, 0)
                                        .atZone(CHICAGO)
                                        .toOffsetDateTime()
                                        .minusDays(1)
                                        .toString(),
                        "items", List.of(Map.of("food_id", banana.get("id").asString(), "quantity_g", 120))));
        assertThat(meal.status()).as(meal.text()).isEqualTo(201);
        assertThat(meal.text()).contains("107"); // 89 kcal per 100 g × 120 g
    }

    @Test
    void anOutageLeavesTheSearchWorking() {
        down = true;
        try {
            Res r = get(user(), "/v1/foods/search?q=greek");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text()).contains("Greek yoghurt");
        } finally {
            down = false;
        }
    }

    @Test
    void namesAreTidied() {
        JsonNode root = JsonMapper.builder().build().readTree(ANSWER);
        List<FoodDataCentral.Food> foods = FoodDataCentral.parse(root);
        assertThat(foods).hasSize(2);
        assertThat(foods.get(0).name()).isEqualTo("Bananas, raw");
        assertThat(foods.get(0).fibreG()).isEqualTo(2.6);
    }
}
