package com.vitallink.api.nutrition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looks a barcode up in Open Food Facts (https://world.openfoodfacts.org): free, open data, no key. Only the
 * barcode is sent, never anything about the user. Values per 100 g, as on a label.
 */
@Component
public class OpenFoodFacts {

    private static final Logger log = LoggerFactory.getLogger(OpenFoodFacts.class);

    /** A product's label values (per 100 g); null where the product doesn't say. */
    public record Product(
            String name,
            String brand,
            Double servingG,
            double kcal,
            double proteinG,
            double carbsG,
            double fatG,
            Double fibreG) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public Optional<Product> lookup(String barcode) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://world.openfoodfacts.org/api/v2/product/" + barcode
                        + ".json?fields=product_name,brands,serving_quantity,nutriments"))
                .timeout(Duration.ofSeconds(8))
                // Open Food Facts asks every app to name itself.
                .header("User-Agent", "VitalLink/0.2 (capstone health app)")
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                return Optional.empty();
            }
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Open Food Facts answered " + res.statusCode());
            }
            JsonNode root = json.readTree(res.body());
            if (root.path("status").asInt(0) != 1) {
                return Optional.empty();
            }
            return product(root.path("product"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            log.warn("Open Food Facts unreachable: {}", e.getMessage());
            throw new IllegalStateException("Open Food Facts unreachable", e);
        }
    }

    static Optional<Product> product(JsonNode p) {
        JsonNode n = p.path("nutriments");
        String name = p.path("product_name").asString("").strip();
        if (name.isEmpty() || !n.has("energy-kcal_100g")) {
            return Optional.empty(); // no usable label: the user adds it by hand
        }
        String brand = p.path("brands").asString("").split(",")[0].strip();
        double serving = p.path("serving_quantity").asDouble(0);
        return Optional.of(new Product(
                name.length() > 80 ? name.substring(0, 80).strip() : name,
                brand.isEmpty() ? null : (brand.length() > 80 ? brand.substring(0, 80) : brand),
                serving > 0 && serving <= 5000 ? serving : null,
                clamp(n.path("energy-kcal_100g").asDouble(0), 900),
                clamp(n.path("proteins_100g").asDouble(0), 100),
                clamp(n.path("carbohydrates_100g").asDouble(0), 100),
                clamp(n.path("fat_100g").asDouble(0), 100),
                n.has("fiber_100g") ? clamp(n.path("fiber_100g").asDouble(0), 100) : null));
    }

    private static double clamp(double v, double max) {
        return Math.round(Math.max(0, Math.min(max, v)) * 10) / 10.0;
    }
}
