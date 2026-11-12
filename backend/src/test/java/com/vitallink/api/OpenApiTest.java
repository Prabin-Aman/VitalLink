package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The OpenAPI document names fields the way the API sends them (packages/api types are generated from it). */
class OpenApiTest extends ApiTest {

    @Test
    void schemasUseSnakeCaseNames() {
        Res doc = get(null, "/openapi.json");
        assertThat(doc.status()).isEqualTo(200);
        JsonNode schemas = doc.at("/components/schemas");
        assertThat(schemas.at("/Dashboard/properties").propertyNames())
                .contains("generated_at", "health_score", "health_score_delta_7d", "metrics");
        List<String> camel = new ArrayList<>();
        for (String schema : schemas.propertyNames()) {
            for (String field : schemas.get(schema).path("properties").propertyNames()) {
                if (!field.equals(field.toLowerCase())) camel.add(schema + "." + field);
            }
        }
        assertThat(camel).isEmpty();
    }

    @Test
    void tokenDerivedArgumentsAreNotParameters() {
        JsonNode doc = get(null, "/openapi.json").body();
        assertThat(doc.at("/components/schemas").propertyNames())
                .doesNotContain("CurrentUser", "ConsentGate", "Claims", "PhoneCaller");
        assertThat(doc.at("/paths/~1v1~1me/get/parameters").isMissingNode()).isTrue();
        assertThat(doc.at("/paths/~1v1~1users~1{user_id}~1health~1dashboard/get/parameters/0/name")
                        .asString())
                .isEqualTo("user_id");
    }
}
