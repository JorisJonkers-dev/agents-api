package com.jorisjonkers.personalstack.agents.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Agents API")
                    .description("Conversation and messaging service for jorisjonkers.dev")
                    .version("1.0.0"),
            ).addServersItem(Server().url("https://agents.jorisjonkers.dev").description("Production"))
            .addServersItem(Server().url("http://localhost:8082").description("Local development"))
            .components(
                Components()
                    .addSecuritySchemes(
                        "xUserId",
                        SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .`in`(SecurityScheme.In.HEADER)
                            .name("X-User-Id")
                            .description("User identifier injected by Traefik forward-auth"),
                    ),
            ).addSecurityItem(SecurityRequirement().addList("xUserId"))

    @Bean
    fun validationProblemSchemaCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.components
                ?.schemas
                ?.get("FieldError")
                ?.properties
                ?.remove("rejectedValue")
        }
}
