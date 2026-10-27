package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Browsers may call the API only from the configured origins (a comma-separated list locally). */
@TestPropertySource(properties = "vitallink.cors-origin=http://portal.test , http://mobile.test")
class CorsTest extends ApiTest {

    @ParameterizedTest
    @CsvSource({"http://portal.test,200", "http://mobile.test,200", "http://evil.test,403"})
    void preflightAllowsOnlyListedOrigins(String origin, int status) throws Exception {
        var res = mvc.perform(MockMvcRequestBuilders.options("/v1/me")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).isEqualTo(status);
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(status == 200 ? origin : null);
    }
}
