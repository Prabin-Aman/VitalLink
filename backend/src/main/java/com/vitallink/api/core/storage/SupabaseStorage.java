package com.vitallink.api.core.storage;

import com.vitallink.api.core.Clock;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Supabase Storage (one private bucket) over its REST API, with the service-role key. Signed URLs come
 * from Supabase, so files never pass through this API.
 *
 * <ul>
 *   <li>upload: {@code POST /object/upload/sign/{bucket}/{key}} → a signed PUT URL
 *   <li>stat: {@code HEAD /object/authenticated/{bucket}/{key}} → size and type as stored
 *   <li>download: {@code POST /object/sign/{bucket}/{key}} with {@code expiresIn} → a signed GET URL
 *   <li>delete: {@code DELETE /object/{bucket}} with {@code prefixes}
 * </ul>
 */
public class SupabaseStorage implements FileStorage {

    private final String base;
    private final String bucket;
    private final String serviceKey;
    private final ObjectMapper json;
    private final HttpClient http;

    public SupabaseStorage(String supabaseUrl, String bucket, String serviceKey, ObjectMapper json, HttpClient http) {
        this.base = supabaseUrl.replaceAll("/+$", "") + "/storage/v1";
        this.bucket = bucket;
        this.serviceKey = serviceKey;
        this.json = json;
        this.http = http;
    }

    private static String encodePath(String key) {
        return Arrays.stream(key.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new StorageUnavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageUnavailable(e);
        }
    }

    private JsonNode ok(HttpResponse<String> res) {
        if (res.statusCode() / 100 != 2) {
            throw new StorageUnavailable(new IOException("Supabase Storage answered HTTP " + res.statusCode()));
        }
        return json.readTree(res.body().isEmpty() ? "{}" : res.body());
    }

    @Override
    public SignedUpload signUpload(String key, String contentType) {
        JsonNode body = ok(send(request("/object/upload/sign/" + bucket + "/" + encodePath(key))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()));
        // Supabase signed upload URLs last two hours; the record's pending upload is cleaned up after a day.
        return new SignedUpload(
                base + body.get("url").asString(),
                "PUT",
                Map.of("Content-Type", contentType),
                Clock.now().plusHours(2));
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        HttpResponse<String> res = send(request("/object/authenticated/" + bucket + "/" + encodePath(key))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());
        if (res.statusCode() == 404 || res.statusCode() == 400) {
            return Optional.empty();
        }
        if (res.statusCode() / 100 != 2) {
            throw new StorageUnavailable(new IOException("Supabase Storage answered HTTP " + res.statusCode()));
        }
        long size = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        String type = res.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return Optional.of(new StoredObject(size, type.split(";")[0].strip()));
    }

    @Override
    public String signDownload(String key, String fileName) {
        JsonNode body = ok(send(request("/object/sign/" + bucket + "/" + encodePath(key))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"expiresIn\": " + TTL.toSeconds() + "}"))
                .build()));
        String url = base + body.get("signedURL").asString();
        return url + "&download=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8);
    }

    @Override
    public void delete(String key) {
        String payload = json.writeValueAsString(Map.of("prefixes", new String[] {key}));
        HttpResponse<String> res = send(request("/object/" + bucket)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(payload))
                .build());
        if (res.statusCode() / 100 != 2 && res.statusCode() != 404) {
            throw new StorageUnavailable(new IOException("Supabase Storage answered HTTP " + res.statusCode()));
        }
    }

    /** Storage is down or refused; the API answers 503 and nothing is half-done. */
    public static class StorageUnavailable extends RuntimeException {
        StorageUnavailable(Throwable cause) {
            super(cause);
        }
    }
}
