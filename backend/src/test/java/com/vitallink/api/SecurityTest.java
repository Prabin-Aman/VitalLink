package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.DevTokens;
import com.vitallink.api.core.security.TokenVerifier;
import com.vitallink.api.support.ApiTest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Token verification. */
class SecurityTest extends ApiTest {

    private static JWTClaimsSet.Builder payload() {
        long now = Instant.now().getEpochSecond();
        return new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "a@example.com")
                .audience("authenticated")
                .issuer(TokenVerifier.DEV_ISSUER)
                .issueTime(new Date(now * 1000))
                .expirationTime(new Date((now + 600) * 1000));
    }

    private static String sign(JWTClaimsSet.Builder b) {
        return DevTokens.sign(b.build(), TEST_SECRET);
    }

    @Test
    void missingTokenIs401() {
        Res res = get(null, "/v1/me");
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.code()).isEqualTo("unauthorized");
    }

    static Stream<Arguments> badTokens() {
        long now = Instant.now().getEpochSecond();
        UnaryOperator<JWTClaimsSet.Builder> none = b -> b;
        return Stream.of(
                Arguments.of("expired", sign(payload().expirationTime(new Date((now - 10) * 1000)))),
                Arguments.of("audience", sign(payload().audience("someone-else"))),
                Arguments.of("issuer", sign(payload().issuer("https://evil.example"))),
                Arguments.of(
                        "signature", DevTokens.sign(none.apply(payload()).build(), "not-the-secret-" + "y".repeat(32))),
                Arguments.of("no-exp", sign(payload().expirationTime(null))),
                Arguments.of("garbage", "not-a-jwt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badTokens")
    void badTokensAre401(String name, String token) {
        assertThat(get("Bearer " + token, "/v1/me").status()).isEqualTo(401);
    }

    @Test
    void tokenWithoutEmailIs403() {
        Res res = get("Bearer " + sign(payload().claim("email", null)), "/v1/me");
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.code()).isEqualTo("email_required");
    }

    @Test
    void devTokensCannotBeMintedInProduction() {
        Settings prod = new Settings("production", null, null, null, "x".repeat(40));
        assertThatThrownBy(() -> DevTokens.mint(prod, UUID.randomUUID(), "a@example.com", true, null, null, 60, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionNeverAcceptsHs256() {
        // Even a correctly signed dev token is refused once ENV=production.
        Settings prod = new Settings("production", null, "https://example.supabase.co", null, TEST_SECRET);
        TokenVerifier verifier = new TokenVerifier(prod);
        assertThatThrownBy(() -> verifier.verify("Bearer " + sign(payload())))
                .isInstanceOfSatisfying(
                        ApiError.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void oversizedBodiesAreRefusedBeforeParsing() {
        String big = "{\"x\": \"" + "a".repeat(2 * 1024 * 1024) + "\"}";
        Res res = post(user(), "/v1/me/health/manual", big);
        assertThat(res.status()).isEqualTo(413);
        assertThat(res.code()).isEqualTo("body_too_large");
    }

    @Test
    void requestIdIsReturned() throws Exception {
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/me"))
                .andReturn()
                .getResponse();
        String id = r.getHeader("X-Request-ID");
        assertThat(id).startsWith("req_");
        assertThat(json.readTree(r.getContentAsString()).at("/error/request_id").asString())
                .isEqualTo(id);
    }
}
