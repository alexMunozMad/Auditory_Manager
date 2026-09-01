package com.qualifyze.audit.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryWindowTest {

	// A request accepted on 2026-01-01 (UTC). In this scope the processing duration is the
	// default 7 (docs/02 §3).
	private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T10:00:00Z");
	private static final int PROCESSING_DAYS = 7;

	@ParameterizedTest
	@CsvSource({
			// level,     earliest,     latest      (docs/00 A1, month = 30 days)
			"ESSENTIALS, 2026-01-22, 2026-04-24", //  +21 = +(28-7) ,  +113 = +(120-7)
			"ADVANCED,   2026-01-15, 2026-03-25", //  +14 = +(21-7) ,   +83 = +(90-7)
	})
	void windowIsRequestDatePlusTierDaysMinusProcessing(SubscriptionLevel level,
			LocalDate expectedEarliest, LocalDate expectedLatest) {

		DeliveryWindow window = DeliveryWindow.forRequest(level, REQUESTED_AT, PROCESSING_DAYS);

		assertEquals(expectedEarliest, window.earliestAuditDate());
		assertEquals(expectedLatest, window.latestAuditDate());
	}

	@Test
	void premiumEarliestIsFlooredAtTomorrow_notTheWeekInThePast() {
		// raw earliest = 2026-01-01 + (0 - 7) = 2025-12-25, a week before the request.
		// The floor is the day after the request (docs/01 §4, docs/02 §3 CHECK).
		DeliveryWindow window = DeliveryWindow.forRequest(SubscriptionLevel.PREMIUM, REQUESTED_AT, PROCESSING_DAYS);

		assertEquals(LocalDate.parse("2026-01-02"), window.earliestAuditDate());
		assertEquals(LocalDate.parse("2026-01-24"), window.latestAuditDate()); // +23 = +(30-7)
	}

	@Test
	void containsIsInclusiveOfBothEnds() {
		DeliveryWindow window = DeliveryWindow.forRequest(SubscriptionLevel.ESSENTIALS, REQUESTED_AT, PROCESSING_DAYS);

		assertTrue(window.contains(window.earliestAuditDate()));
		assertTrue(window.contains(window.latestAuditDate()));
		assertFalse(window.contains(window.earliestAuditDate().minusDays(1)));
		assertFalse(window.contains(window.latestAuditDate().plusDays(1)));
	}
}
