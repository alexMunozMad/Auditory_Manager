package com.qualifyze.audit.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRequestTest {

	private static final UUID ID = UUID.randomUUID();
	private static final UUID CLIENT = UUID.randomUUID();
	private static final UUID SITE = UUID.randomUUID();
	private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T10:00:00Z");
	private static final int PROCESSING_DAYS = 7;
	private static final String KEY = "idem-key-1";

	private static AuditRequest pending() {
		return AuditRequest.accept(ID, CLIENT, SITE, SubscriptionLevel.ESSENTIALS, REQUESTED_AT, PROCESSING_DAYS, KEY);
	}

	@Test
	void acceptFreezesTheContractAndLandsPending() {
		AuditRequest request = pending();

		assertEquals(ID, request.id());
		assertEquals(CLIENT, request.clientId());
		assertEquals(SITE, request.siteId());
		assertEquals(SubscriptionLevel.ESSENTIALS, request.subscriptionLevel());
		assertEquals(REQUESTED_AT, request.requestedAt());
		assertEquals(DeliveryWindow.forRequest(SubscriptionLevel.ESSENTIALS, REQUESTED_AT, PROCESSING_DAYS),
				request.deliveryWindow());
		assertEquals(KEY, request.idempotencyKey());

		assertEquals(RequestStatus.PENDING, request.status());
		assertNull(request.auditId());
		assertNull(request.availableToClientAt());
		assertNull(request.cancellationReason());
	}

	@Test
	void rehydrateRestoresEveryPersistedField() {
		DeliveryWindow window = DeliveryWindow.forRequest(SubscriptionLevel.ADVANCED, REQUESTED_AT, PROCESSING_DAYS);
		UUID auditId = UUID.randomUUID();
		LocalDate accessDate = LocalDate.parse("2026-03-01");

		AuditRequest request = AuditRequest.rehydrate(ID, CLIENT, SITE, SubscriptionLevel.ADVANCED,
				REQUESTED_AT, window, KEY, RequestStatus.SCHEDULED, auditId, accessDate, null);

		assertEquals(ID, request.id());
		assertEquals(SubscriptionLevel.ADVANCED, request.subscriptionLevel());
		assertEquals(window, request.deliveryWindow());
		assertEquals(RequestStatus.SCHEDULED, request.status());
		assertEquals(auditId, request.auditId());
		assertEquals(accessDate, request.availableToClientAt());
	}

	@Test
	void acceptRejectsABlankIdempotencyKey() {
		assertThrows(IllegalArgumentException.class, () ->
				AuditRequest.accept(ID, CLIENT, SITE, SubscriptionLevel.PREMIUM, REQUESTED_AT, PROCESSING_DAYS, "  "));
	}

	@Test
	void attachMovesPendingToScheduledWithAnAuditAndAnAccessDate() {
		AuditRequest request = pending();
		UUID auditId = UUID.randomUUID();
		LocalDate accessDate = LocalDate.parse("2026-02-01");

		request.attachTo(auditId, accessDate);

		assertEquals(RequestStatus.SCHEDULED, request.status());
		assertEquals(auditId, request.auditId());
		assertEquals(accessDate, request.availableToClientAt());
	}

	@Test
	void attachIsRejectedUnlessPending() {
		AuditRequest request = pending();
		request.markUnschedulable();

		assertThrows(IllegalStateException.class,
				() -> request.attachTo(UUID.randomUUID(), LocalDate.parse("2026-02-01")));
	}

	@Test
	void markUnschedulableMovesPendingToUnschedulable() {
		AuditRequest request = pending();

		request.markUnschedulable();

		assertEquals(RequestStatus.UNSCHEDULABLE, request.status());
	}

	@Test
	void markUnschedulableIsRejectedUnlessPending() {
		AuditRequest request = pending();
		request.attachTo(UUID.randomUUID(), LocalDate.parse("2026-02-01"));

		assertThrows(IllegalStateException.class, request::markUnschedulable);
	}

	@Test
	void fulfilMovesScheduledToFulfilled() {
		AuditRequest request = pending();
		request.attachTo(UUID.randomUUID(), LocalDate.parse("2026-02-01"));

		request.fulfil();

		assertEquals(RequestStatus.FULFILLED, request.status());
	}

	@Test
	void fulfilIsRejectedUnlessScheduled() {
		assertThrows(IllegalStateException.class, () -> pending().fulfil());
	}

	@Test
	void cancelFromPendingRecordsTheReason() {
		AuditRequest request = pending();

		request.cancel("client withdrew");

		assertEquals(RequestStatus.CANCELLED, request.status());
		assertEquals("client withdrew", request.cancellationReason());
	}

	@Test
	void cancelFromScheduledIsAllowed() {
		AuditRequest request = pending();
		request.attachTo(UUID.randomUUID(), LocalDate.parse("2026-02-01"));

		request.cancel("no longer needed");

		assertEquals(RequestStatus.CANCELLED, request.status());
	}

	@Test
	void cancelIsRejectedOnceFulfilled() {
		AuditRequest request = pending();
		request.attachTo(UUID.randomUUID(), LocalDate.parse("2026-02-01"));
		request.fulfil();

		assertThrows(IllegalStateException.class, () -> request.cancel("too late"));
	}

	@Test
	void cancelRequiresAReason() {
		assertThrows(IllegalArgumentException.class, () -> pending().cancel("  "));
	}

	// ESSENTIALS, requested 2026-01-01: window 2026-01-22 .. 2026-04-24 (min 28, max 120, processing 7).

	@Test
	void reportCommitmentProjectsTheWindowAcrossTheProcessingDuration() {
		ReportCommitment commitment = pending().reportCommitment(PROCESSING_DAYS);

		assertEquals(LocalDate.parse("2026-01-29"), commitment.reportNoEarlierThan()); // earliest + 7
		assertEquals(LocalDate.parse("2026-05-01"), commitment.reportNoLaterThan());   // latest + 7
	}

	@Test
	void accessDateIsNeverBeforeTheClientsMinimumWindow() {
		AuditRequest request = pending();

		// publication after the 28-day floor → the publication date wins
		assertEquals(LocalDate.parse("2026-03-01"), request.accessDateFor(LocalDate.parse("2026-03-01")));
		// publication before the floor → floored at requested_at + 28
		assertEquals(LocalDate.parse("2026-01-29"), request.accessDateFor(LocalDate.parse("2026-01-10")));
	}

	@Test
	void canReuseWhenBothA7ConditionsHold() {
		assertTrue(pending().canReuse(
				LocalDate.parse("2026-01-15"), LocalDate.parse("2027-01-15"), PROCESSING_DAYS));
	}

	@Test
	void cannotReuseAnAuditThatExpiresBeforeTheAccessDate() {
		assertFalse(pending().canReuse(
				LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-20"), PROCESSING_DAYS));
	}

	@Test
	void cannotReuseWhenTheAccessDateWouldBreachTheContractualCeiling() {
		// publication 2026-06-01 → access 2026-06-01, past reportNoLaterThan 2026-05-01
		assertFalse(pending().canReuse(
				LocalDate.parse("2026-06-01"), LocalDate.parse("2030-01-01"), PROCESSING_DAYS));
	}
}
