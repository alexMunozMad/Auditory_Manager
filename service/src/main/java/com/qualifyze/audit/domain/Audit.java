package com.qualifyze.audit.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The visit and the resulting report - a fact about a site, valid for one year, shared by every
 * request attached to it (docs/01, A7).
 *
 * <p>The calendar arithmetic lives here, in one place (docs/01 §3):
 * <pre>
 *   publishedAt = auditDate + processingDuration
 *   validUntil  = publishedAt + 1 calendar year   (29/02 -&gt; 28/02, the conservative reading, A7)
 * </pre>
 *
 * <p>The state machine (docs/01 §5) advances independently of the request's own; every transition
 * starts from a named state. {@code DISCARDED} is reachable only from {@code SCHEDULED} and is
 * unconditional - an audit with no remaining demand is dropped, not held in stock.
 */
public class Audit {

	/** In this scope the on-site duration never varies from the default (docs/00 A1, docs/07 §6). */
	public static final int DEFAULT_AUDIT_DURATION_DAYS = 1;

	/**
	 * Days from the audit date to publication (docs/00 A1). The request-time window and the client's
	 * report commitment are both projected across this value; in this scope it never varies.
	 */
	public static final int DEFAULT_PROCESSING_DURATION_DAYS = 7;

	private final UUID id;
	private final UUID siteId;
	private final UUID auditorId;
	private final LocalDate auditDate;
	private final int auditDurationDays;
	private final int processingDurationDays;

	private AuditStatus status;
	private LocalDate publishedAt;
	private LocalDate validUntil;
	private String reportUri;

	private Audit(UUID id, UUID siteId, UUID auditorId, LocalDate auditDate,
			int auditDurationDays, int processingDurationDays) {
		this.id = id;
		this.siteId = siteId;
		this.auditorId = auditorId;
		this.auditDate = auditDate;
		this.auditDurationDays = auditDurationDays;
		this.processingDurationDays = processingDurationDays;
		this.status = AuditStatus.SCHEDULED;
	}

	/**
	 * The worker books an auditor on a date: the audit row comes into existence already
	 * {@code SCHEDULED} (there is no "awaiting an auditor" state, docs/02 §2).
	 */
	public static Audit schedule(UUID id, UUID siteId, UUID auditorId, LocalDate auditDate,
			int processingDurationDays) {

		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(siteId, "siteId");
		Objects.requireNonNull(auditorId, "auditorId");
		Objects.requireNonNull(auditDate, "auditDate");
		if (processingDurationDays < 0) {
			throw new IllegalArgumentException("processingDurationDays must be >= 0");
		}
		return new Audit(id, siteId, auditorId, auditDate, DEFAULT_AUDIT_DURATION_DAYS, processingDurationDays);
	}

	/**
	 * Rebuild an audit from its persisted row. Persistence-facing - the caller is a repository and
	 * every field is taken as stored, with no invariant re-run: the row already satisfied the CHECKs
	 * of docs/02 §2 on the way in. A rehydrated audit equals the one that was saved.
	 */
	public static Audit rehydrate(UUID id, UUID siteId, UUID auditorId, LocalDate auditDate,
			int auditDurationDays, int processingDurationDays, AuditStatus status,
			LocalDate publishedAt, LocalDate validUntil, String reportUri) {

		Audit audit = new Audit(id, siteId, auditorId, auditDate, auditDurationDays, processingDurationDays);
		audit.status = status;
		audit.publishedAt = publishedAt;
		audit.validUntil = validUntil;
		audit.reportUri = reportUri;
		return audit;
	}

	/** The date this audit will publish on, projected from the audit date (docs/00 A1). */
	public LocalDate projectedPublicationDate() {
		return auditDate.plusDays(processingDurationDays);
	}

	/** The last day a request could still reuse this audit, projected while it is not yet published (docs/02 §6). */
	public LocalDate projectedValidUntil() {
		return projectedPublicationDate().plusYears(1);
	}

	/** SCHEDULED → IN_PROGRESS: the audit date has passed. Advanced by the daily sweep, not an endpoint (docs/03 §7). */
	public void begin() {
		requireStatus(AuditStatus.SCHEDULED, "begin");
		this.status = AuditStatus.IN_PROGRESS;
	}

	/**
	 * IN_PROGRESS → PUBLISHED: the report arrives from outside the system. Freezes {@code publishedAt}
	 * and {@code validUntil} together (mirrors {@code audit_validity_paired}, {@code audit_published_has_report}).
	 */
	public void publish(String reportUri) {
		requireStatus(AuditStatus.IN_PROGRESS, "publish");
		if (reportUri == null || reportUri.isBlank()) {
			throw new IllegalArgumentException("a report reference is required to publish");
		}
		this.publishedAt = projectedPublicationDate();
		this.validUntil = this.publishedAt.plusYears(1);
		this.reportUri = reportUri;
		this.status = AuditStatus.PUBLISHED;
	}

	/**
	 * SCHEDULED → DISCARDED: the last attached request was withdrawn. Unconditional - an audit is
	 * never worth performing without demand merely to hold it (docs/01 §5).
	 */
	public void discard() {
		requireStatus(AuditStatus.SCHEDULED, "discard");
		this.status = AuditStatus.DISCARDED;
	}

	private void requireStatus(AuditStatus required, String action) {
		if (status != required) {
			throw new IllegalStateException("cannot " + action + " an audit that is " + status);
		}
	}

	public UUID id() {
		return id;
	}

	public UUID siteId() {
		return siteId;
	}

	public UUID auditorId() {
		return auditorId;
	}

	public LocalDate auditDate() {
		return auditDate;
	}

	public int auditDurationDays() {
		return auditDurationDays;
	}

	public int processingDurationDays() {
		return processingDurationDays;
	}

	public AuditStatus status() {
		return status;
	}

	public LocalDate publishedAt() {
		return publishedAt;
	}

	public LocalDate validUntil() {
		return validUntil;
	}

	public String reportUri() {
		return reportUri;
	}
}
