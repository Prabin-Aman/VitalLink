package com.vitallink.api.care;

import com.vitallink.api.core.ApiError;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looks doctors up in the NPI Registry (https://npiregistry.cms.hhs.gov), the US government's public list of
 * licensed providers: free, no key. Only the search words are sent, never anything about the user. Texas only,
 * the app's service area.
 */
@Component
public class DoctorSearch {

    private static final Logger log = LoggerFactory.getLogger(DoctorSearch.class);
    private static final String REGISTRY = "https://npiregistry.cms.hhs.gov/api/";
    /** How many listings a search shows. The registry is asked for its maximum, since the city filter drops many. */
    private static final int SHOWN = 20;

    /** Everyday words, in the registry's terms (cardiologists are filed under "Cardiovascular Disease"). */
    private static final Map<String, String> SPECIALTIES = Map.ofEntries(
            Map.entry("cardiology", "Cardiovascular Disease"),
            Map.entry("cardiologist", "Cardiovascular Disease"),
            Map.entry("heart", "Cardiovascular Disease"),
            Map.entry("dermatologist", "Dermatology"),
            Map.entry("skin", "Dermatology"),
            Map.entry("pediatrician", "Pediatrics"),
            Map.entry("orthopedics", "Orthopaedic Surgery"),
            Map.entry("orthopedic", "Orthopaedic Surgery"),
            Map.entry("family doctor", "Family Medicine"),
            Map.entry("primary care", "Family Medicine"),
            Map.entry("psychiatrist", "Psychiatry"),
            Map.entry("neurologist", "Neurology"),
            Map.entry("gynecologist", "Obstetrics & Gynecology"),
            Map.entry("gynecology", "Obstetrics & Gynecology"),
            Map.entry("obgyn", "Obstetrics & Gynecology"),
            Map.entry("eye doctor", "Ophthalmology"),
            Map.entry("ophthalmologist", "Ophthalmology"));

    /** One listing; any part the registry doesn't have is null. */
    public record Doctor(String npi, String name, String credential, String specialty, String address, String phone) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public List<Doctor> search(String name, String specialty, String city) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REGISTRY + "?" + query(name, specialty, city)))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("NPI Registry answered HTTP {}", res.statusCode());
                throw unavailable();
            }
            return parse(json.readTree(res.body()), city);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (java.io.IOException e) {
            log.warn("NPI Registry unreachable: {}", e.getMessage());
            throw unavailable();
        }
    }

    private static ApiError unavailable() {
        return new ApiError(
                503, "doctor_search_unavailable", "Doctor search isn't reachable right now. Try again shortly.");
    }

    /** One word is a last name; more is first name then last name. A trailing {@code *} matches a word's start. */
    static String query(String name, String specialty, String city) {
        List<String> q = new ArrayList<>(List.of("version=2.1", "enumeration_type=NPI-1", "state=TX", "limit=200"));
        if (name != null && !name.isBlank()) {
            String[] parts = name.strip().replaceFirst("(?i)^dr\\.?\\s+", "").split("\\s+", 2);
            if (parts.length > 1) {
                q.add("first_name=" + encode(parts[0] + "*"));
                q.add("last_name=" + encode(parts[1] + "*"));
            } else {
                q.add("last_name=" + encode(parts[0] + "*"));
            }
        }
        if (specialty != null && !specialty.isBlank()) {
            String asked = specialty.strip();
            q.add("taxonomy_description="
                    + encode(SPECIALTIES.getOrDefault(asked.toLowerCase(Locale.ROOT), asked) + "*"));
        }
        if (city != null && !city.isBlank()) {
            q.add("city=" + encode(city.strip()));
        }
        return String.join("&", q);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * The registry reports a search it can't run as {@code Errors} with HTTP 200: that's no results. Its city
     * also matches mailing addresses, so only people practising in {@code city} are kept; trainees are left out.
     */
    static List<Doctor> parse(JsonNode root, String city) {
        List<Doctor> out = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            JsonNode basic = r.path("basic");
            String name = (title(basic.path("first_name").asString("")) + " "
                            + title(basic.path("last_name").asString("")))
                    .strip();
            if (name.isEmpty()) {
                continue;
            }
            String credential =
                    basic.path("credential").asString("").replace(".", "").strip();
            String specialty = null;
            for (JsonNode t : r.path("taxonomies")) {
                if (specialty == null || t.path("primary").asBoolean(false)) {
                    specialty = blankToNull(t.path("desc").asString("").replaceAll("[,\\s]+$", ""));
                }
            }
            String address = null;
            String phone = null;
            String town = "";
            for (JsonNode a : r.path("addresses")) {
                if ("LOCATION".equals(a.path("address_purpose").asString(""))) {
                    String street = title(a.path("address_1").asString(""));
                    town = title(a.path("city").asString(""));
                    address = blankToNull(street.isEmpty() ? town : town.isEmpty() ? street : street + ", " + town);
                    phone = blankToNull(a.path("telephone_number").asString(""));
                }
            }
            boolean elsewhere = city != null && !city.isBlank() && !town.equalsIgnoreCase(city.strip());
            boolean trainee = specialty != null && specialty.startsWith("Student in an Organized");
            if (elsewhere || trainee || out.size() == SHOWN) {
                continue;
            }
            out.add(new Doctor(
                    r.path("number").asString(""), name, blankToNull(credential), specialty, address, phone));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }

    /** The registry writes in capitals: "701 MATLOCK RD" becomes "701 Matlock Rd". */
    static String title(String s) {
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char c : s.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return b.toString();
    }
}
