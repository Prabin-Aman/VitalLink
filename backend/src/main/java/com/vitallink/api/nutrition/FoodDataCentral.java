package com.vitallink.api.nutrition;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searches USDA FoodData Central (https://fdc.nal.usda.gov): free, public domain (CC0), a data.gov key
 * (USDA_API_KEY, 1,000 requests an hour). Only the search words are sent. Everyday foods only (Foundation, SR Legacy
 * and the survey foods); packaged products come from Open Food Facts by barcode. Values per 100 g.
 *
 * <p>Without a key, or when USDA can't be reached, it returns nothing and the search uses VitalLink's own foods.
 * Answers are kept for a day, so the same words don't spend the hourly allowance twice.
 */
@Component
public class FoodDataCentral {

    private static final Logger log = LoggerFactory.getLogger(FoodDataCentral.class);

    static final int PAGE = 15;
    static final int CACHE_ENTRIES = 500;
    static final Duration CACHE_FOR = Duration.ofDays(1);

    /** One food: per 100 g, with a typical portion when USDA gives one. */
    public record Food(
            int fdcId,
            String name,
            double servingG,
            double kcal,
            double proteinG,
            double carbsG,
            double fatG,
            double fibreG) {}

    private record Cached(Instant at, List<Food> foods) {}

    private final String key;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    public FoodDataCentral(
            @Value("${vitallink.usda-api-key:}") String key,
            @Value("${vitallink.usda-api-url:https://api.nal.usda.gov/fdc/v1}") String baseUrl) {
        this.key = key == null ? "" : key.strip();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean configured() {
        return !key.isEmpty();
    }

    /** Foods matching the words, best first; empty without a key or when USDA doesn't answer. */
    public List<Food> search(String query) {
        if (!configured()) {
            return List.of();
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached c = cache.get(q);
            if (c != null && c.at().plus(CACHE_FOR).isAfter(Instant.now())) {
                return c.foods();
            }
        }
        String url = baseUrl + "/foods/search?api_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&dataType=" + URLEncoder.encode("Foundation,SR Legacy,Survey (FNDDS)", StandardCharsets.UTF_8)
                + "&pageSize=" + PAGE;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String why = res.body() == null ? "" : res.body().replaceAll("\\s+", " ");
                log.warn(
                        "FoodData Central answered {}: {}",
                        res.statusCode(),
                        why.substring(0, Math.min(200, why.length())));
                return List.of();
            }
            List<Food> foods = parse(json.readTree(res.body()));
            synchronized (cache) {
                cache.put(q, new Cached(Instant.now(), foods));
            }
            return foods;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException e) {
            log.warn("FoodData Central unreachable: {}", e.toString());
            return List.of();
        }
    }

    /** The foods in a search answer that have energy and the three macros. */
    public static List<Food> parse(JsonNode root) {
        List<Food> out = new ArrayList<>();
        for (JsonNode f : root.path("foods")) {
            int id = f.path("fdcId").asInt(0);
            String name = f.path("description").asString("").strip();
            if (id <= 0 || name.isEmpty()) {
                continue;
            }
            Map<String, Double> n = new LinkedHashMap<>();
            for (JsonNode x : f.path("foodNutrients")) {
                String number = x.path("nutrientNumber").asString("");
                if (x.has("value") && !n.containsKey(number)) {
                    n.put(number, x.path("value").asDouble());
                }
            }
            // Energy: kcal (208), else the Atwater figures Foundation foods carry (958 specific, 957 general).
            Double kcal = n.get("208") != null ? n.get("208") : n.get("958") != null ? n.get("958") : n.get("957");
            if (kcal == null && n.get("268") != null) {
                kcal = n.get("268") / 4.184; // kJ
            }
            Double protein = n.get("203");
            Double fat = n.get("204");
            Double carbs = n.get("205");
            if (kcal == null || protein == null || fat == null || carbs == null) {
                continue; // not enough to log a meal with
            }
            out.add(new Food(
                    id,
                    tidy(name),
                    serving(f),
                    clamp(kcal, 900),
                    clamp(protein, 100),
                    clamp(carbs, 100),
                    clamp(fat, 100),
                    clamp(n.getOrDefault("291", 0.0), 100)));
        }
        return out;
    }

    /** "BANANAS, RAW" and over-long names made readable and at most 80 characters. */
    static String tidy(String name) {
        String s = name.equals(name.toUpperCase(Locale.ROOT))
                ? name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT)
                : name;
        return s.length() > 80 ? s.substring(0, 80).strip() : s;
    }

    /** A portion USDA lists (survey foods), else 100 g. */
    static double serving(JsonNode f) {
        for (JsonNode m : f.path("foodMeasures")) {
            double g = m.path("gramWeight").asDouble(0);
            if (g > 0 && g <= 5000) {
                return Math.round(g * 10) / 10.0;
            }
        }
        return 100;
    }

    private static double clamp(double v, double max) {
        return Math.round(Math.max(0, Math.min(max, v)) * 10) / 10.0;
    }
}
