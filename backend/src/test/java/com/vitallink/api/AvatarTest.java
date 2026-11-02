package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Settings › Change photo: signed upload, confirm, the photo link on /me and /me/account, remove. */
class AvatarTest extends ApiTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

    int put(String url, byte[] body, String type) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(URI.create(url).getPath())
                        .content(body)
                        .header(HttpHeaders.CONTENT_TYPE, type))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void uploadConfirmShowRemove() throws Exception {
        String u = user();
        assertThat(get(u, "/v1/me").at("/avatar_url").isNull()).isTrue();

        Res up = post(u, "/v1/me/avatar/upload", Map.of("content_type", "image/png", "size", PNG.length));
        assertThat(up.status()).as(up.text()).isEqualTo(200);
        String key = up.at("/key").asString();
        // Not there yet: confirm says so.
        assertThat(post(u, "/v1/me/avatar/confirm", Map.of("key", key)).code()).isEqualTo("file_missing");
        assertThat(put(up.at("/upload_url").asString(), PNG, "image/png")).isEqualTo(200);

        Res me = post(u, "/v1/me/avatar/confirm", Map.of("key", key));
        assertThat(me.status()).as(me.text()).isEqualTo(200);
        assertThat(me.at("/avatar_url").asString()).contains("/local-storage/");
        assertThat(get(u, "/v1/me/account").at("/avatar_url").asString()).contains("/local-storage/");

        assertThat(delete(u, "/v1/me/avatar").status()).isEqualTo(204);
        assertThat(get(u, "/v1/me").at("/avatar_url").isNull()).isTrue();
    }

    @Test
    void refusesOtherFilesAndOtherPeoplesUploads() throws Exception {
        String u = user();
        assertThat(post(u, "/v1/me/avatar/upload", Map.of("content_type", "application/pdf", "size", 10))
                        .status())
                .isEqualTo(415);
        assertThat(post(u, "/v1/me/avatar/upload", Map.of("content_type", "image/png", "size", 3_000_000))
                        .status())
                .isEqualTo(413);

        // A stored file whose type isn't an image is deleted and refused.
        Res up = post(u, "/v1/me/avatar/upload", Map.of("content_type", "image/png", "size", 5));
        put(up.at("/upload_url").asString(), "hello".getBytes(), "text/plain");
        assertThat(post(u, "/v1/me/avatar/confirm", Map.of("key", up.at("/key").asString()))
                        .code())
                .isEqualTo("file_rejected");

        // Someone else's key is never accepted.
        String other = user();
        Res theirs = post(other, "/v1/me/avatar/upload", Map.of("content_type", "image/png", "size", PNG.length));
        put(theirs.at("/upload_url").asString(), PNG, "image/png");
        assertThat(post(
                                u,
                                "/v1/me/avatar/confirm",
                                Map.of("key", theirs.at("/key").asString()))
                        .status())
                .isEqualTo(422);
    }
}
