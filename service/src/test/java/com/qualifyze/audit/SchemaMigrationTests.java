package com.qualifyze.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void contextLoadsWithMigrationsApplied() {
		// If Liquibase failed, the Spring context would not start and this test would
		// not run. This is the wiring proof — Testcontainers + Liquibase + Spring Boot —
		// which is the real risk in the scaffolding ticket.
	}

	@Test
	void oneAuditorPerSitePerDayIsEnforcedByTheDatabase() {
		UUID supplier = UUID.randomUUID();
		UUID siteA = UUID.randomUUID();
		UUID siteB = UUID.randomUUID();
		UUID auditor = UUID.randomUUID();
		LocalDate day = LocalDate.of(2026, 10, 5);

		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)")
				.params(supplier, "Acme Pharma").update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
				.params(siteA, supplier, "Warehouse A").update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
				.params(siteB, supplier, "Warehouse B").update();
		jdbc.sql("INSERT INTO auditor (id, name) VALUES (?, ?)")
				.params(auditor, "Dana Ito").update();

		jdbc.sql("INSERT INTO audit (id, site_id, auditor_id, audit_date, status) "
						+ "VALUES (?, ?, ?, ?, 'SCHEDULED')")
				.params(UUID.randomUUID(), siteA, auditor, day).update();

		// Same auditor, same day, a different site: audit_one_per_auditor_per_day rejects
		// it. The point isn't that the constraint is listed in the catalog — it's that a
		// second insert actually bounces, surfaced as a catchable DuplicateKeyException
		// (ADR 0004), not a torn transaction.
		assertThrows(DuplicateKeyException.class, () ->
				jdbc.sql("INSERT INTO audit (id, site_id, auditor_id, audit_date, status) "
								+ "VALUES (?, ?, ?, ?, 'SCHEDULED')")
						.params(UUID.randomUUID(), siteB, auditor, day).update());
	}
}
