package com.qualifyze.audit.persistence;

import com.qualifyze.audit.TestcontainersConfiguration;
import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRepositoryTest {

	@Autowired
	AuditRepository audits;

	@Autowired
	JdbcClient jdbc;

	private UUID siteId;

	@BeforeEach
	void seedSite() {
		siteId = insertSite();
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

	private UUID insertAuditor(boolean active) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO auditor (id, name, active) VALUES (?, ?, ?)")
				.params(id, "auditor-" + id, active).update();
		return id;
	}

	private void insertPublishedAudit(UUID site, UUID auditor, LocalDate auditDate, LocalDate validUntil) {
		jdbc.sql("""
				INSERT INTO audit (id, site_id, auditor_id, audit_date, status, published_at, valid_until, report_uri)
				VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, 's3://reports/x.pdf')
				""")
				.params(UUID.randomUUID(), site, auditor, auditDate, validUntil.minusYears(1), validUntil)
				.update();
	}

	private void insertAudit(UUID site, UUID auditor, LocalDate auditDate, String status) {
		jdbc.sql("INSERT INTO audit (id, site_id, auditor_id, audit_date, status) VALUES (?, ?, ?, ?, ?)")
				.params(UUID.randomUUID(), site, auditor, auditDate, status).update();
	}

	@Test
	void saveWritesTheAuditAndItsScheduledEventInOneTransaction() {
		UUID auditor = insertAuditor(true);
		Audit audit = Audit.schedule(UUID.randomUUID(), siteId, auditor, LocalDate.parse("2026-07-27"), 7);

		audits.save(audit);

		String status = jdbc.sql("SELECT status FROM audit WHERE id = ?")
				.param(audit.id()).query(String.class).single();
		assertEquals(AuditStatus.SCHEDULED.name(), status);

		long events = jdbc.sql("""
				SELECT count(*) FROM outbox_event
				 WHERE aggregate_id = ? AND aggregate_type = 'audit' AND event_type = 'AuditScheduled'
				""").param(audit.id()).query(Long.class).single();
		assertEquals(1, events);
	}

	@Test
	void theInFlightIndexRejectsASecondScheduledAuditForASite() {
		audits.save(Audit.schedule(UUID.randomUUID(), siteId, insertAuditor(true), LocalDate.parse("2026-07-27"), 7));

		assertThrows(DuplicateKeyException.class, () ->
				audits.save(Audit.schedule(UUID.randomUUID(), siteId, insertAuditor(true), LocalDate.parse("2026-08-10"), 7)));
	}

	@Test
	void theAuditorDayIndexRejectsASecondAuditForTheSameAuditorAndDate() {
		UUID auditor = insertAuditor(true);
		LocalDate day = LocalDate.parse("2026-07-27");
		audits.save(Audit.schedule(UUID.randomUUID(), siteId, auditor, day, 7));

		assertThrows(DuplicateKeyException.class, () ->
				audits.save(Audit.schedule(UUID.randomUUID(), insertSite(), auditor, day, 7)));
	}

	@Test
	void aSecondPublishedAuditForASiteIsAllowedAndTheLatestValidityWins() {
		UUID auditor = insertAuditor(true);
		insertPublishedAudit(siteId, auditor, LocalDate.parse("2024-06-01"), LocalDate.parse("2025-06-08"));
		insertPublishedAudit(siteId, auditor, LocalDate.parse("2025-06-01"), LocalDate.parse("2026-06-08"));

		Audit current = audits.findCurrentPublishedBySite(siteId).orElseThrow();

		assertEquals(LocalDate.parse("2026-06-08"), current.validUntil());
	}

	@Test
	void findInFlightBySiteReturnsTheScheduledAuditAndIgnoresPublishedOnes() {
		insertPublishedAudit(siteId, insertAuditor(true), LocalDate.parse("2024-06-01"), LocalDate.parse("2025-06-08"));
		Audit scheduled = Audit.schedule(UUID.randomUUID(), siteId, insertAuditor(true), LocalDate.parse("2026-07-27"), 7);
		audits.save(scheduled);

		Audit found = audits.findInFlightBySite(siteId).orElseThrow();

		assertEquals(scheduled.id(), found.id());
		assertEquals(AuditStatus.SCHEDULED, found.status());
	}

	@Test
	void findInFlightBySiteIsEmptyWhenTheOnlyAuditIsPublished() {
		insertPublishedAudit(siteId, insertAuditor(true), LocalDate.parse("2024-06-01"), LocalDate.parse("2025-06-08"));

		assertTrue(audits.findInFlightBySite(siteId).isEmpty());
	}

	@Test
	void isAuditorFreeOnFlipsOnceAnAuditIsBookedThatDay() {
		UUID auditor = insertAuditor(true);
		LocalDate day = LocalDate.parse("2026-07-27");
		assertTrue(audits.isAuditorFreeOn(auditor, day));

		audits.save(Audit.schedule(UUID.randomUUID(), siteId, auditor, day, 7));

		assertFalse(audits.isAuditorFreeOn(auditor, day));
	}

	@Test
	void activeAuditorsByLoadOrdersLeastLoadedFirstAndExcludesInactiveAndDiscarded() {
		UUID busy = insertAuditor(true);
		UUID idle = insertAuditor(true);
		UUID inactive = insertAuditor(false);
		LocalDate windowStart = LocalDate.parse("2026-01-01");

		insertPublishedAudit(insertSite(), busy, LocalDate.parse("2026-03-01"), LocalDate.parse("2027-03-08"));
		insertPublishedAudit(insertSite(), busy, LocalDate.parse("2026-03-02"), LocalDate.parse("2027-03-09"));
		insertAudit(insertSite(), idle, LocalDate.parse("2026-03-01"), "DISCARDED"); // does not count

		List<UUID> byLoad = audits.activeAuditorsByLoad(windowStart);

		// other tests in the class leave active auditors behind, so assert the relative order,
		// not the exact list: the least-loaded of our three comes before the busiest.
		assertTrue(byLoad.indexOf(idle) < byLoad.indexOf(busy), "idle auditor should rank ahead of the busy one");
		assertFalse(byLoad.contains(inactive), "inactive auditors are not eligible");
	}
}
