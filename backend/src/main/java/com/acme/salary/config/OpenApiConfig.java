package com.acme.salary.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OpenAPI document at {@code /v3/api-docs} and Swagger UI at
 * {@code /swagger-ui.html} (NFR-5.4). The document is generated from the controllers and
 * DTOs, so the published contract cannot drift from the code.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI salaryManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ACME Salary Management API")
                        .version("v1")
                        .description("""
                                Employee compensation, monthly payroll runs and payslips.

                                All endpoints except /auth/login and /actuator/health require a
                                bearer token. Amounts are JSON strings at two decimal places.""")
                        .license(new License().name("Proprietary — ACME internal")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
