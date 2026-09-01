package com.qualifyze.audit.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditTest {

	private static final UUID ID = UUID.randomUUID();
	private static final UUID SITE = UUID.randomUUID();
	private static final UUID AUDITOR = UUID.randomUUID();
	private static final int PROCESSING_DAYS = 7;

	private static Audit scheduledOn(LocalDate auditDate) {
		return Audit.schedule(ID, SITE, AUDITOR, auditDate, PROCESSING_DAYS);
	}

	@Test
	void scheduleCreatesAScheduledAuditWithNothingPublishedYet() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));

		assertEquals(AuditStatus.SCHEDULED, audit.status());
		assertEquals(SITE, audit.siteId());
		assertEquals(AUDITOR, audit.auditorId());
		assertNull(audit.publishedAt());
		assertNull(audit.validUntil());
		assertNull(audit.reportUri());
	}

	@Test
	void publicationDateIsProjectedFromTheAuditDate() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));

		assertEquals(LocalDate.parse("2026-08-03"), audit.projectedPublicationDate());        // +7
		assertEquals(LocalDate.parse("2027-08-03"), audit.projectedValidUntil());             // +7, +1 year
	}

	@Test
	void beginMovesScheduledToInProgress() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));

		audit.begin();

		assertEquals(AuditStatus.IN_PROGRESS, audit.status());
	}

	@Test
	void beginIsRejectedUnlessScheduled() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));
		audit.begin();

		assertThrows(IllegalStateException.class, audit::begin);
	}

	@Test
	void publishSetsTheReportAndFreezesTheValidityPeriod() {
		// A7 example: an audit published on 03/08/2026 is reusable until 03/08/2027.
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));
		audit.begin();

		audit.publish("s3://reports/018f7c3d.pdf");

		assertEquals(AuditStatus.PUBLISHED, audit.status());
		assertEquals(LocalDate.parse("2026-08-03"), audit.publishedAt());
		assertEquals(LocalDate.parse("2027-08-03"), audit.validUntil());
		assertEquals("s3://reports/018f7c3d.pdf", audit.reportUri());
	}

	@Test
	void validityUsesCalendarYearArithmetic_29FebMapsTo28Feb() {
		// audit_date + 7 lands on a leap day; +1 calendar year is the conservative 28/02 (A7).
		Audit audit = scheduledOn(LocalDate.parse("2028-02-22")); // + 7 = 2028-02-29
		audit.begin();

		audit.publish("s3://reports/leap.pdf");

		assertEquals(LocalDate.parse("2028-02-29"), audit.publishedAt());
		assertEquals(LocalDate.parse("2029-02-28"), audit.validUntil());
	}

	@Test
	void publishIsRejectedUnlessInProgress() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));

		assertThrows(IllegalStateException.class, () -> audit.publish("s3://reports/x.pdf"));
	}

	@Test
	void publishRequiresAReportReference() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));
		audit.begin();

		assertThrows(IllegalArgumentException.class, () -> audit.publish("  "));
	}

	@Test
	void discardMovesScheduledToDiscarded() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));

		audit.discard();

		assertEquals(AuditStatus.DISCARDED, audit.status());
	}

	@Test
	void discardIsReachableOnlyFromScheduled() {
		Audit audit = scheduledOn(LocalDate.parse("2026-07-27"));
		audit.begin();

		assertThrows(IllegalStateException.class, audit::discard);
	}

	@Test
	void scheduleRejectsANegativeProcessingDuration() {
		assertThrows(IllegalArgumentException.class,
				() -> Audit.schedule(ID, SITE, AUDITOR, LocalDate.parse("2026-07-27"), -1));
	}
}
