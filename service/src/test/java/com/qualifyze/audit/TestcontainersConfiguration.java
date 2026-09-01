package com.qualifyze.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		// PostgreSQL 18 — the deployment target (CLAUDE.md, docs/06). The schema uses
		// features (partial unique indexes, plpgsql triggers) an in-memory DB can't fake.
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));
	}

}
