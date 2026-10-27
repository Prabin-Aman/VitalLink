package com.vitallink.api.core.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.consent.ConsentGate;
import com.vitallink.api.core.security.Claims;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document at /openapi.json (Swagger UI at /docs). packages/api/src/types.ts follows it. */
@Configuration
public class OpenApiConfig {

    static {
        // Filled in from the token by WebConfig's resolvers, not sent by the client.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class, ConsentGate.class, Claims.class);
    }

    /**
     * Schemas use the JSON names the API sends. swagger-core reads models with its own (Jackson 2) mapper,
     * which doesn't see spring.jackson.property-naming-strategy, so it would document camelCase names.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    /** Shared routes read {user_id} through the ConsentGate, so springdoc doesn't see it; declare it. */
    @Bean
    OpenApiCustomizer userIdPathParameter() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (!path.contains("{user_id}")) return;
            item.readOperations().forEach(op -> {
                boolean declared = op.getParameters() != null
                        && op.getParameters().stream().anyMatch(p -> "user_id".equals(p.getName()));
                if (!declared) {
                    op.addParametersItem(new PathParameter()
                            .name("user_id")
                            .description("The account whose data is read (it must have shared it with the caller)")
                            .schema(new StringSchema().format("uuid")));
                }
            });
        });
    }

    @Bean
    OpenAPI vitalLinkOpenApi() {
        return new OpenAPI()
                .info(new Info().title("VitalLink API").version("v1").description("""
                                Consumer health and fitness API. `/v1/me/…` is the caller's own data; `/v1/users/{user_id}/…` \
                                is someone else's data, read-only and consent-checked. Errors are \
                                `{"error": {"code", "message", "detail", "request_id"}}`. Design: backend/docs/."""))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "Supabase access token (local: a dev token from the DevToken tool)")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
