package com.qualifyze.audit.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. springdoc generates the spec from the controllers; this only sets the title and
 * the one thing a reader needs to know before trying a call: auth is a stub (docs/07 §7).
 *
 * <p>Spec at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}. To try it against a real
 * database, run {@code TestAuditSchedulingApplication} — it starts the app with a Testcontainers
 * PostgreSQL, so Swagger UI comes up on {@code localhost:8080} with Docker doing the rest.
 */
@Configuration
class OpenApiConfig {

	@Bean
	OpenAPI auditSchedulingOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Audit scheduling API")
				.version("v1")
				.description("Client API for raising audit requests (Qualifyze backend challenge). "
						+ "Authentication is a deliberate stub: the X-Client-Id header stands in for "
						+ "the JWT subject (docs/07 §7)."));
	}
}
