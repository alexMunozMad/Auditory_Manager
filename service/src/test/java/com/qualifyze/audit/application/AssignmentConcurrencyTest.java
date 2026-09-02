package com.qualifyze.audit.application;

import com.qualifyze.audit.TestcontainersConfiguration;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.SubscriptionLevel;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

/**
 * The concurrency proof (docs/05 §9, docs/06 §3) — the evidence shown in the defence.
 *
 * <p>Many requests for different sites, one shared subscription window, so every request competes
 * for the same early dates. Several worker threads drain the queue at once. Then the same run with
 * {@code UNIQUE (auditor_id, audit_date)} dropped, to show the net actually catches something.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(OrderAnnotation.class)
class AssignmentConcurrencyTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
	private static final int WORKER_THREADS = 6;

	@Autowired
	AssignmentWorker worker;

	@Autowired
	AuditRequestRepository requests;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	Clock clock;

	private UUID clientId;

	@BeforeEach
	void resetAndSeedClient() {
		given(clock.instant()).willReturn(NOW);
		given(clock.getZone()).willReturn(ZoneOffset.UTC);

		// the workload query and the placement search read every active auditor globally, so this
		// test owns the whole pool — other classes' leftover auditors would dilute the contention.
		jdbc.sql("DELETE FROM audit_request").update();
		jdbc.sql("DELETE FROM audit").update();
		jdbc.sql("DELETE FROM outbox_event").update();
		jdbc.sql("DELETE FROM auditor").update();

		UUID supplierId = UUID.randomUUID();
		clientId = UUID.randomUUID();
		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)").params(supplierId, "sup-" + supplierId).update();
		jdbc.sql("""
				INSERT INTO client (id, name, contact_email, subscription_level_code, subscription_valid_until)
				VALUES (?, ?, ?, 'ESSENTIALS', DATE '2027-01-01')
				""").params(clientId, "cli-" + clientId, "ops@example.com").update();
	}

	private void seedAuditors(int count) {
		for (int i = 0; i < count; i++) {
			UUID id = UUID.randomUUID();
			jdbc.sql("INSERT INTO auditor (id, name) VALUES (?, ?)").params(id, "aud-" + i + "-" + id).update();
		}
	}

	/** {@code count} pending requests, each for its own fresh site, all sharing one window. */
	private void seedPendingRequests(int count) {
		UUID supplierId = jdbc.sql("SELECT id FROM supplier LIMIT 1").query(UUID.class).single();
		for (int i = 0; i < count; i++) {
			UUID siteId = UUID.randomUUID();
			jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
					.params(siteId, supplierId, "site-" + i + "-" + siteId).update();
			AuditRequest request = AuditRequest.accept(UUID.randomUUID(), clientId, siteId,
					SubscriptionLevel.ESSENTIALS, NOW, 7, "key-" + siteId);
			requests.save(request);
		}
	}

	private void drainQueueWith(int threads) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> running = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				running.add(pool.submit(() -> {
					start.await();
					for (int guard = 0; guard < 200 && worker.runOnce() > 0; guard++) {
						// keep claiming until the queue is empty
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : running) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private long scalar(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	@Test
	@Order(1)
	void manyWorkersPlaceEveryRequestWithNoDoubleBooking() throws Exception {
		int requestCount = 60; // > worker batch size, so several worker transactions run at once
		seedAuditors(6);
		seedPendingRequests(requestCount);

		drainQueueWith(WORKER_THREADS);

		// every request placed, none lost, none in a bad state
		assertEquals(requestCount, scalar("SELECT count(*) FROM audit_request WHERE status = 'SCHEDULED'"));
		assertEquals(0, scalar("SELECT count(*) FROM audit_request WHERE status NOT IN ('SCHEDULED', 'PENDING')"));

		// zero (auditor_id, audit_date) collisions
		assertEquals(0, scalar("""
				SELECT count(*) FROM (
				  SELECT auditor_id, audit_date FROM audit GROUP BY auditor_id, audit_date HAVING count(*) > 1
				) collisions
				"""));

		// distinct sites → one new audit per request, and exactly one event per transition
		assertEquals(requestCount, scalar("SELECT count(*) FROM audit"));
		assertEquals(requestCount, scalar("SELECT count(*) FROM outbox_event WHERE event_type = 'AuditScheduled'"));
		assertEquals(requestCount, scalar("SELECT count(*) FROM outbox_event WHERE event_type = 'AuditRequestScheduled'"));

		// no auditor ran away with the batch — least-loaded keeps the spread tight
		long maxLoad = scalar("SELECT max(c) FROM (SELECT count(*) c FROM audit GROUP BY auditor_id) loads");
		long minLoad = scalar("SELECT min(c) FROM (SELECT count(*) c FROM audit GROUP BY auditor_id) loads");
		assertTrue(maxLoad - minLoad <= WORKER_THREADS,
				"load spread " + minLoad + ".." + maxLoad + " wider than the worker count");
	}

	@Test
	@Order(2)
	void withoutTheAuditorDayIndexTheSameRunDoubleBooks() throws Exception {
		jdbc.sql("ALTER TABLE audit DROP CONSTRAINT audit_one_per_auditor_per_day").update();
		try {
			seedAuditors(3);              // few auditors, many requests, several worker transactions at once
			seedPendingRequests(60);

			drainQueueWith(WORKER_THREADS);

			long collisions = scalar("""
					SELECT count(*) FROM (
					  SELECT auditor_id, audit_date FROM audit GROUP BY auditor_id, audit_date HAVING count(*) > 1
					) collisions
					""");
			assertTrue(collisions > 0,
					"without UNIQUE (auditor_id, audit_date) the concurrent workers should double-book");
		} finally {
			jdbc.sql("DELETE FROM audit_request").update();
			jdbc.sql("DELETE FROM audit").update();
			jdbc.sql("DELETE FROM outbox_event").update();
			jdbc.sql("ALTER TABLE audit ADD CONSTRAINT audit_one_per_auditor_per_day UNIQUE (auditor_id, audit_date)").update();
		}
	}
}
