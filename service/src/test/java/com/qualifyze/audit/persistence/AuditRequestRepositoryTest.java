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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		// claimPending() reads the whole PENDING queue; start each test from an empty one so the
		// ordering and SKIP-LOCKED assertions see only the rows the test itself seeds.
		jdbc.sql("DELETE FROM audit_request").update();

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

	/** A PENDING row with a controlled deadline - the window arithmetic is exercised elsewhere. */
	private UUID insertPending(LocalDate earliest, LocalDate latest, String key) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO audit_request
				  (id, client_id, site_id, requested_at, subscription_level_code,
				   earliest_audit_date, latest_audit_date, status, idempotency_key)
				VALUES (?, ?, ?, TIMESTAMPTZ '2018-01-01 00:00:00+00', 'ESSENTIALS', ?, ?, 'PENDING', ?)
				""")
				.params(id, clientId, siteId, earliest, latest, key)
				.update();
		return id;
	}

	private UUID insertScheduledAudit() {
		UUID auditorId = UUID.randomUUID();
		UUID auditId = UUID.randomUUID();
		jdbc.sql("INSERT INTO auditor (id, name) VALUES (?, ?)")
				.params(auditorId, "auditor-" + auditorId).update();
		jdbc.sql("""
				INSERT INTO audit (id, site_id, auditor_id, audit_date, status)
				VALUES (?, ?, ?, DATE '2026-03-01', 'SCHEDULED')
				""")
				.params(auditId, siteId, auditorId).update();
		return auditId;
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

	@Test
	void claimPendingReturnsPendingRowsEarliestDeadlineFirst() {
		UUID march = insertPending(LocalDate.parse("2019-03-01"), LocalDate.parse("2019-03-31"), "q-mar");
		UUID january = insertPending(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-01-31"), "q-jan");
		UUID february = insertPending(LocalDate.parse("2019-02-01"), LocalDate.parse("2019-02-28"), "q-feb");

		List<UUID> claimed = transactionTemplate.execute(tx ->
				repository.claimPending(10).stream().map(AuditRequest::id).toList());

		assertEquals(List.of(january, february, march), claimed);
	}

	@Test
	void claimPendingSkipsRowsAnotherTransactionHolds() throws Exception {
		UUID first = insertPending(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-01-10"), "claim-a");
		UUID second = insertPending(LocalDate.parse("2019-01-02"), LocalDate.parse("2019-01-11"), "claim-b");

		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<UUID> heldByOther = pool.submit(() -> transactionTemplate.execute(tx -> {
				UUID claimed = repository.claimPending(1).get(0).id();
				firstClaimed.countDown();
				awaitQuietly(release);
				return claimed;
			}));

			assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));
			UUID claimedHere = transactionTemplate.execute(tx -> repository.claimPending(1).get(0).id());
			release.countDown();
			UUID claimedThere = heldByOther.get(5, TimeUnit.SECONDS);

			assertNotEquals(claimedHere, claimedThere);
			assertEquals(Set.of(first, second), Set.of(claimedHere, claimedThere));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void updatePersistsTheTransitionColumns() {
		AuditRequest request = acceptedRequest("key-update");
		repository.save(request);
		UUID auditId = insertScheduledAudit();

		request.attachTo(auditId, LocalDate.parse("2026-02-15"));
		repository.update(request);

		AuditRequest reloaded = repository.findByClientAndIdempotencyKey(clientId, "key-update").orElseThrow();
		assertEquals(RequestStatus.SCHEDULED, reloaded.status());
		assertEquals(auditId, reloaded.auditId());
		assertEquals(LocalDate.parse("2026-02-15"), reloaded.availableToClientAt());
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
