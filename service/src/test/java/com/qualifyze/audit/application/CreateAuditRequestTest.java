package com.qualifyze.audit.application;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CreateAuditRequestTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	CreateAuditRequest createAuditRequest;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	Clock clock;

	private UUID clientId;
	private UUID siteId;

	@BeforeEach
	void freezeTimeAndSeed() {
		given(clock.instant()).willReturn(NOW);

		clientId = UUID.randomUUID();
		siteId = insertSite();
		insertClient(clientId, "ESSENTIALS", "2027-01-01");
	}

	private UUID insertSite() {
		UUID supplierId = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)")
				.params(supplierId, "supplier-" + supplierId).update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
				.params(id, supplierId, "site-" + id).update();
		return id;
	}

	private void insertClient(UUID id, String level, String validUntil) {
		jdbc.sql("""
				INSERT INTO client (id, name, contact_email, subscription_level_code, subscription_valid_until)
				VALUES (?, ?, ?, ?, CAST(? AS date))
				""")
				.params(id, "client-" + id, "ops@example.com", level, validUntil).update();
	}

	private CreateAuditRequestCommand command() {
		return new CreateAuditRequestCommand(clientId, siteId, "idem-key-1");
	}

	@Test
	void acceptsTheRequestAsPendingWithTheFrozenWindow() {
		AuditRequest request = createAuditRequest.execute(command());

		assertEquals(RequestStatus.PENDING, request.status());
		assertEquals(SubscriptionLevel.ESSENTIALS, request.subscriptionLevel());
		assertEquals(NOW, request.requestedAt());

		String persistedStatus = jdbc.sql("SELECT status FROM audit_request WHERE id = ?")
				.param(request.id()).query(String.class).single();
		assertEquals("PENDING", persistedStatus);

		long events = jdbc.sql("""
				SELECT count(*) FROM outbox_event
				 WHERE aggregate_id = ? AND event_type = 'AuditRequestCreated'
				""").param(request.id()).query(Long.class).single();
		assertEquals(1, events);
	}

	@Test
	void rejectsAnUnknownSite() {
		var cmd = new CreateAuditRequestCommand(clientId, UUID.randomUUID(), "idem-key-2");

		assertThrows(SiteNotFoundException.class, () -> createAuditRequest.execute(cmd));
	}

	@Test
	void rejectsAnUnknownClient() {
		var cmd = new CreateAuditRequestCommand(UUID.randomUUID(), siteId, "idem-key-3");

		assertThrows(UnknownClientException.class, () -> createAuditRequest.execute(cmd));
	}

	@Test
	void rejectsAnExpiredSubscription() {
		UUID lapsed = UUID.randomUUID();
		insertClient(lapsed, "ESSENTIALS", "2025-06-01"); // before the frozen NOW
		var cmd = new CreateAuditRequestCommand(lapsed, siteId, "idem-key-4");

		assertThrows(SubscriptionNotActiveException.class, () -> createAuditRequest.execute(cmd));
	}

	@Test
	void replaysTheOriginalRequestWhenTheSameKeyAndBodyRepeat() {
		AuditRequest first = createAuditRequest.execute(command());
		AuditRequest replay = createAuditRequest.execute(command());

		assertEquals(first.id(), replay.id());

		Long rows = jdbc.sql("SELECT count(*) FROM audit_request WHERE client_id = ? AND idempotency_key = ?")
				.params(clientId, "idem-key-1").query(Long.class).single();
		assertEquals(1L, rows);

		Long events = jdbc.sql("""
				SELECT count(*) FROM outbox_event
				 WHERE aggregate_id = ? AND event_type = 'AuditRequestCreated'
				""").param(first.id()).query(Long.class).single();
		assertEquals(1L, events);
	}

	@Test
	void rejectsAReusedKeyCarryingADifferentSite() {
		createAuditRequest.execute(command());
		var reused = new CreateAuditRequestCommand(clientId, insertSite(), "idem-key-1");

		assertThrows(IdempotencyKeyReusedException.class, () -> createAuditRequest.execute(reused));
	}

	@Test
	void requestDateIsReadInUtcFromTheClock() {
		AuditRequest request = createAuditRequest.execute(command());

		assertEquals(NOW.atZone(ZoneOffset.UTC).toLocalDate().plusDays(21),
				request.deliveryWindow().earliestAuditDate()); // ESSENTIALS: +(28-7)
	}
}
