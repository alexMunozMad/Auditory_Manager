package com.qualifyze.audit.persistence;

import com.qualifyze.audit.TestcontainersConfiguration;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.RequestStatus;
import com.qualifyze.audit.domain.SubscriptionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRequestRepositoryTest {

	@Autowired
	AuditRequestRepository repository;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	private UUID clientId;
	private UUID siteId;

	@BeforeEach
	void seedClientAndSite() {
		UUID supplierId = UUID.randomUUID();
		clientId = UUID.randomUUID();
		siteId = UUID.randomUUID();

		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)")
				.params(supplierId, "supplier-" + supplierId).update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
				.params(siteId, supplierId, "site-" + siteId).update();
		jdbc.sql("""
				INSERT INTO client (id, name, contact_email, subscription_level_code, subscription_valid_until)
				VALUES (?, ?, ?, 'ESSENTIALS', DATE '2027-01-01')
				""")
				.params(clientId, "client-" + clientId, "ops@example.com").update();
	}

	private AuditRequest acceptedRequest(String idempotencyKey) {
		return AuditRequest.accept(UUID.randomUUID(), clientId, siteId, SubscriptionLevel.ESSENTIALS,
				Instant.parse("2026-01-01T10:00:00Z"), 7, idempotencyKey);
	}

	@Test
	void saveWritesTheRequestAndItsOutboxEventInOneTransaction() {
		AuditRequest request = acceptedRequest("key-1");

		repository.save(request);

		String status = jdbc.sql("SELECT status FROM audit_request WHERE id = ?")
				.param(request.id()).query(String.class).single();
		assertEquals(RequestStatus.PENDING.name(), status);

		long events = jdbc.sql("""
				SELECT count(*) FROM outbox_event
				 WHERE aggregate_id = ? AND aggregate_type = 'audit_request'
				   AND event_type = 'AuditRequestCreated'
				""")
				.param(request.id()).query(Long.class).single();
		assertEquals(1, events);
	}

	@Test
	void aFailureAfterTheInsertsRollsBackBoth() {
		AuditRequest request = acceptedRequest("key-2");

		assertThrows(RuntimeException.class, () ->
				transactionTemplate.executeWithoutResult(tx -> {
					repository.save(request);
					throw new RuntimeException("boom after both inserts");
				}));

		assertEquals(0, count("SELECT count(*) FROM audit_request WHERE id = ?", request.id()));
		assertEquals(0, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", request.id()));
	}

	private long count(String sql, Object arg) {
		return jdbc.sql(sql).param(arg).query(Long.class).single();
	}

	@Test
	void findByClientAndIdempotencyKeyRehydratesTheStoredRequest() {
		AuditRequest saved = acceptedRequest("key-find");
		repository.save(saved);

		AuditRequest found = repository.findByClientAndIdempotencyKey(clientId, "key-find").orElseThrow();

		assertEquals(saved.id(), found.id());
		assertEquals(saved.siteId(), found.siteId());
		assertEquals(saved.requestedAt(), found.requestedAt());
		assertEquals(saved.deliveryWindow(), found.deliveryWindow());
		assertEquals(RequestStatus.PENDING, found.status());
	}

	@Test
	void findByClientAndIdempotencyKeyIsEmptyWhenNoRowMatches() {
		assertEquals(java.util.Optional.empty(),
				repository.findByClientAndIdempotencyKey(clientId, "absent"));
	}
}
