package com.vitallink.api.core.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document at /openapi.json (Swagger UI at /docs). packages/api/src/types.ts follows it. */
@Configuration
public class OpenApiConfig {

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
