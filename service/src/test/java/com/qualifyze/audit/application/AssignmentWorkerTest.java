package com.qualifyze.audit.application;

import com.qualifyze.audit.TestcontainersConfiguration;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.SubscriptionLevel;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AssignmentWorkerTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	AssignmentWorker worker;

	@Autowired
	AuditRequestRepository requests;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	Clock clock;

	private UUID clientId;
	private UUID siteId;

	@BeforeEach
	void resetAndSeed() {
		given(clock.instant()).willReturn(NOW);
		given(clock.getZone()).willReturn(ZoneOffset.UTC);

		jdbc.sql("DELETE FROM audit_request").update();
		jdbc.sql("DELETE FROM audit").update();
		jdbc.sql("DELETE FROM outbox_event").update();

		UUID supplierId = UUID.randomUUID();
		clientId = UUID.randomUUID();
		siteId = UUID.randomUUID();
		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)").params(supplierId, "sup-" + supplierId).update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)").params(siteId, supplierId, "site-" + siteId).update();
		jdbc.sql("""
				INSERT INTO client (id, name, contact_email, subscription_level_code, subscription_valid_until)
				VALUES (?, ?, ?, 'ESSENTIALS', DATE '2027-01-01')
				""").params(clientId, "cli-" + clientId, "ops@example.com").update();
	}

	private UUID insertAuditor() {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO auditor (id, name) VALUES (?, ?)").params(id, "aud-" + id).update();
		return id;
	}

	private UUID insertPublishedAudit(LocalDate auditDate, LocalDate validUntil) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO audit (id, site_id, auditor_id, audit_date, status, published_at, valid_until, report_uri)
				VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, 's3://reports/old.pdf')
				""").params(id, siteId, insertAuditor(), auditDate, validUntil.minusYears(1), validUntil).update();
		return id;
	}

	private AuditRequest pendingRequest() {
		AuditRequest request = AuditRequest.accept(UUID.randomUUID(), clientId, siteId,
				SubscriptionLevel.ESSENTIALS, NOW, 7, "key-" + UUID.randomUUID());
		requests.save(request);
		return request;
	}

	private long count(String sql, Object arg) {
		return jdbc.sql(sql).param(arg).query(Long.class).single();
	}

	@Test
	void schedulesANewAuditForAPendingRequestAndEmitsBothEvents() {
		insertAuditor();
		AuditRequest request = pendingRequest();

		int claimed = worker.runOnce();

		assertEquals(1, claimed);
		String status = jdbc.sql("SELECT status FROM audit_request WHERE id = ?").param(request.id()).query(String.class).single();
		assertEquals("SCHEDULED", status);
		UUID auditId = jdbc.sql("SELECT audit_id FROM audit_request WHERE id = ?").param(request.id()).query(UUID.class).single();
		assertNotNull(auditId);
		LocalDate accessDate = jdbc.sql("SELECT available_to_client_at FROM audit_request WHERE id = ?")
				.param(request.id()).query(LocalDate.class).single();
		assertNotNull(accessDate);

		assertEquals(1, count("SELECT count(*) FROM audit WHERE site_id = ?", siteId));
		assertEquals(1, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'AuditScheduled'", auditId));
		assertEquals(1, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'AuditRequestScheduled'", request.id()));
	}

	@Test
	void reusesAValidPublishedAuditInsteadOfSchedulingANewOne() {
		UUID publishedAuditId = insertPublishedAudit(LocalDate.parse("2025-12-20"), LocalDate.parse("2026-12-27"));
		AuditRequest request = pendingRequest();

		worker.runOnce();

		UUID attachedTo = jdbc.sql("SELECT audit_id FROM audit_request WHERE id = ?").param(request.id()).query(UUID.class).single();
		assertEquals(publishedAuditId, attachedTo);
		assertEquals(1, count("SELECT count(*) FROM audit WHERE site_id = ?", siteId)); // no new audit
	}

	@Test
	void runOnceReturnsZeroWhenTheQueueIsEmpty() {
		assertEquals(0, worker.runOnce());
	}
}
