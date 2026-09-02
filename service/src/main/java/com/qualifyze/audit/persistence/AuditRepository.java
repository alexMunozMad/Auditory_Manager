package com.qualifyze.audit.persistence;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists {@link Audit} rows by hand ({@code JdbcClient}, no JPA — ADR 0004) and answers the reads
 * the assignment worker needs: the two reuse candidates for a site (docs/02 §6), whether an auditor
 * is free on a date, and the active auditors ordered by rolling-window load (A2, docs/02 §7).
 */
@Repository
public class AuditRepository {

	private static final RowMapper<Audit> AUDIT_ROW = (rs, rowNum) -> Audit.rehydrate(
			rs.getObject("id", UUID.class),
			rs.getObject("site_id", UUID.class),
			rs.getObject("auditor_id", UUID.class),
			rs.getObject("audit_date", LocalDate.class),
			rs.getInt("audit_duration_days"),
			rs.getInt("processing_duration_days"),
			AuditStatus.valueOf(rs.getString("status")),
			rs.getObject("published_at", LocalDate.class),
			rs.getObject("valid_until", LocalDate.class),
			rs.getString("report_uri"));

	private static final String SELECT_COLUMNS = """
			SELECT id, site_id, auditor_id, audit_date, audit_duration_days, processing_duration_days,
			       published_at, valid_until, report_uri, status
			  FROM audit
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	AuditRepository(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Write a newly scheduled audit and its {@code AuditScheduled} outbox row (A8, ADR 0002,
	 * docs/04 §3). The two unique indexes ({@code audit_one_per_auditor_per_day},
	 * {@code audit_one_in_flight_per_site}) are the guarantee — a lost race surfaces here as a
	 * {@code DuplicateKeyException}.
	 *
	 * <p>{@code NESTED}: each call runs in its own savepoint inside the worker's transaction, so a
	 * rejected candidate rolls back to the savepoint and the worker retries the next one without
	 * losing the transaction (ADR 0004 — Postgres aborts a transaction on any {@code 23505}).
	 */
	@Transactional(propagation = Propagation.NESTED)
	public void save(Audit audit) {
		jdbc.sql("""
				INSERT INTO audit
				  (id, site_id, auditor_id, audit_date, audit_duration_days, processing_duration_days, status)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""")
				.params(audit.id(),
						audit.siteId(),
						audit.auditorId(),
						audit.auditDate(),
						audit.auditDurationDays(),
						audit.processingDurationDays(),
						audit.status().name())
				.update();

		jdbc.sql("""
				INSERT INTO outbox_event
				  (id, aggregate_type, aggregate_id, event_type, actor, payload, occurred_at)
				VALUES (?, 'audit', ?, 'AuditScheduled', 'worker:assignment', ?::jsonb, now())
				""")
				.params(UUID.randomUUID(), audit.id(), scheduledPayload(audit))
				.update();
	}

	/**
	 * The one possible in-flight audit for a site — {@code SCHEDULED} or {@code IN_PROGRESS}
	 * (index 2, a partial <em>unique</em> index, so at most one). Its {@code valid_until} is still
	 * {@code NULL}; the caller projects validity as {@code audit_date + processing + 1 year}.
	 */
	public Optional<Audit> findInFlightBySite(UUID siteId) {
		return jdbc.sql(SELECT_COLUMNS + " WHERE site_id = ? AND status IN ('SCHEDULED', 'IN_PROGRESS')")
				.param(siteId)
				.query(AUDIT_ROW)
				.optional();
	}

	/** The current published audit for a site, with its real {@code valid_until} (index 3, docs/02 §6). */
	public Optional<Audit> findCurrentPublishedBySite(UUID siteId) {
		return jdbc.sql(SELECT_COLUMNS
						+ " WHERE site_id = ? AND status = 'PUBLISHED' ORDER BY valid_until DESC LIMIT 1")
				.param(siteId)
				.query(AUDIT_ROW)
				.optional();
	}

	/**
	 * Whether the auditor has no audit booked on that date. The ergonomic pre-check only — the
	 * guarantee is {@code UNIQUE (auditor_id, audit_date)}, which arbitrates the race this read
	 * cannot see (docs/01 §3).
	 */
	public boolean isAuditorFreeOn(UUID auditorId, LocalDate date) {
		return jdbc.sql("SELECT NOT exists(SELECT 1 FROM audit WHERE auditor_id = ? AND audit_date = ?)")
				.params(auditorId, date)
				.query(Boolean.class)
				.single();
	}

	/**
	 * Active auditors, least-loaded first over the rolling window that starts at
	 * {@code loadWindowStart}, ties broken by id for reproducibility (A2, docs/02 §7). The load is a
	 * {@code COUNT} of non-discarded audits — never a stored counter, because a lifetime total cannot
	 * express a rolling window.
	 */
	public List<UUID> activeAuditorsByLoad(LocalDate loadWindowStart) {
		return jdbc.sql("""
				SELECT a.id
				  FROM auditor a
				  LEFT JOIN audit x ON x.auditor_id = a.id
				       AND x.audit_date >= ?
				       AND x.status <> 'DISCARDED'
				 WHERE a.active
				 GROUP BY a.id
				 ORDER BY count(x.id), a.id
				""")
				.param(loadWindowStart)
				.query(UUID.class)
				.list();
	}

	/**
	 * Every {@code (audit_date, auditor_id)} already taken in {@code [from, to]} — one read that feeds
	 * the placement search, so it does not query per candidate. Discarded audits release their slot.
	 */
	public Map<LocalDate, Set<UUID>> auditorsBookedBetween(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT audit_date, auditor_id
				  FROM audit
				 WHERE audit_date BETWEEN ? AND ? AND status <> 'DISCARDED'
				""")
				.params(from, to)
				.query(rs -> {
					Map<LocalDate, Set<UUID>> booked = new HashMap<>();
					while (rs.next()) {
						booked.computeIfAbsent(rs.getObject("audit_date", LocalDate.class), day -> new HashSet<>())
								.add(rs.getObject("auditor_id", UUID.class));
					}
					return booked;
				});
	}

	private String scheduledPayload(Audit audit) {
		return json.writeValueAsString(Map.of(
				"auditId", audit.id().toString(),
				"siteId", audit.siteId().toString(),
				"auditorId", audit.auditorId().toString(),
				"auditDate", audit.auditDate().toString()));
	}
}
