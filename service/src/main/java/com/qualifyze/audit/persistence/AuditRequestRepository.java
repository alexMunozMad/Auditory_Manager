package com.qualifyze.audit.persistence;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.DeliveryWindow;
import com.qualifyze.audit.domain.ReportCommitment;
import com.qualifyze.audit.domain.RequestStatus;
import com.qualifyze.audit.domain.SubscriptionLevel;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists {@link AuditRequest} rows by hand ({@code JdbcClient}, no JPA — ADR 0004).
 */
@Repository
public class AuditRequestRepository {

	private static final RowMapper<AuditRequest> REQUEST_ROW = (rs, rowNum) -> AuditRequest.rehydrate(
			rs.getObject("id", UUID.class),
			rs.getObject("client_id", UUID.class),
			rs.getObject("site_id", UUID.class),
			SubscriptionLevel.valueOf(rs.getString("subscription_level_code")),
			rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
			new DeliveryWindow(
					rs.getObject("earliest_audit_date", LocalDate.class),
					rs.getObject("latest_audit_date", LocalDate.class)),
			rs.getString("idempotency_key"),
			RequestStatus.valueOf(rs.getString("status")),
			rs.getObject("audit_id", UUID.class),
			rs.getObject("available_to_client_at", LocalDate.class),
			rs.getString("cancellation_reason"));

	private static final String SELECT_COLUMNS = """
			SELECT id, client_id, site_id, audit_id, requested_at, subscription_level_code,
			       earliest_audit_date, latest_audit_date, available_to_client_at,
			       status, cancellation_reason, idempotency_key
			  FROM audit_request
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	AuditRequestRepository(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Write an accepted request and its {@code AuditRequestCreated} outbox row in one transaction:
	 * the business fact and its trail entry commit together or not at all (A8, ADR 0002).
	 */
	@Transactional
	public void save(AuditRequest request) {
		jdbc.sql("""
				INSERT INTO audit_request
				  (id, client_id, site_id, requested_at, subscription_level_code,
				   earliest_audit_date, latest_audit_date, status, idempotency_key)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
				.params(request.id(),
						request.clientId(),
						request.siteId(),
						request.requestedAt().atOffset(ZoneOffset.UTC),
						request.subscriptionLevel().name(),
						request.deliveryWindow().earliestAuditDate(),
						request.deliveryWindow().latestAuditDate(),
						request.status().name(),
						request.idempotencyKey())
				.update();

		jdbc.sql("""
				INSERT INTO outbox_event
				  (id, aggregate_type, aggregate_id, event_type, actor, payload, occurred_at)
				VALUES (?, 'audit_request', ?, 'AuditRequestCreated', ?, ?::jsonb, now())
				""")
				.params(UUID.randomUUID(),
						request.id(),
						"client:" + request.clientId(),
						createdPayload(request))
				.update();
	}

	/**
	 * The request stored under {@code (client_id, idempotency_key)}, if any. Used by the create path
	 * to answer a retry: the unique index {@code audit_request_idempotency} makes this at most one row
	 * (docs/02 §5, docs/03 §3).
	 */
	public Optional<AuditRequest> findByClientAndIdempotencyKey(UUID clientId, String idempotencyKey) {
		return jdbc.sql(SELECT_COLUMNS + " WHERE client_id = ? AND idempotency_key = ?")
				.params(clientId, idempotencyKey)
				.query(REQUEST_ROW)
				.optional();
	}

	/**
	 * Claim up to {@code limit} pending requests, earliest deadline first, skipping rows another
	 * worker already holds (index 4, ADR 0001). {@code FOR UPDATE SKIP LOCKED} keeps the locks until
	 * the surrounding transaction ends, so this <strong>must be called inside the worker's
	 * transaction</strong> — it is not annotated here on purpose.
	 */
	public List<AuditRequest> claimPending(int limit) {
		return jdbc.sql(SELECT_COLUMNS + """
				 WHERE status = 'PENDING'
				 ORDER BY latest_audit_date
				 FOR UPDATE SKIP LOCKED
				 LIMIT ?
				""")
				.param(limit)
				.query(REQUEST_ROW)
				.list();
	}

	/**
	 * Persist a claimed request's mutable columns after the worker has moved it out of {@code PENDING}
	 * ({@code status}, {@code audit_id}, {@code available_to_client_at}, {@code cancellation_reason}).
	 * The transition's outbox row is written by the worker in the same transaction — the event type
	 * depends on the transition, which the worker decides, not this row.
	 */
	public void update(AuditRequest request) {
		int rows = jdbc.sql("""
				UPDATE audit_request
				   SET status = ?, audit_id = ?, available_to_client_at = ?, cancellation_reason = ?
				 WHERE id = ?
				""")
				.params(request.status().name(),
						request.auditId(),
						request.availableToClientAt(),
						request.cancellationReason(),
						request.id())
				.update();

		if (rows != 1) {
			throw new IllegalStateException(
					"expected to update exactly one audit_request, updated " + rows);
		}
	}

	/**
	 * {@code AuditRequestScheduled} outbox row for a request the worker just attached to an audit
	 * (docs/04 §3). Written by the worker in the same transaction as {@link #update}.
	 */
	public void recordScheduled(AuditRequest request) {
		ReportCommitment commitment = request.reportCommitment(Audit.DEFAULT_PROCESSING_DURATION_DAYS);
		insertTransitionEvent(request.id(), "AuditRequestScheduled", json.writeValueAsString(Map.of(
				"requestId", request.id().toString(),
				"clientId", request.clientId().toString(),
				"auditId", request.auditId().toString(),
				"expectedReportDate", request.availableToClientAt().toString(),
				"commitment", Map.of(
						"reportNoEarlierThan", commitment.reportNoEarlierThan().toString(),
						"reportNoLaterThan", commitment.reportNoLaterThan().toString()))));
	}

	/**
	 * {@code AuditRequestUnschedulable} outbox row for a request whose deadline passed with no
	 * placement (docs/04 §3). {@code reason} is the single value docs/03 §4 defines for this scope.
	 */
	public void recordUnschedulable(AuditRequest request) {
		insertTransitionEvent(request.id(), "AuditRequestUnschedulable", json.writeValueAsString(Map.of(
				"requestId", request.id().toString(),
				"clientId", request.clientId().toString(),
				"reason", "deadline-passed-without-placement",
				"latestAuditDate", request.deliveryWindow().latestAuditDate().toString())));
	}

	private void insertTransitionEvent(UUID requestId, String eventType, String payload) {
		jdbc.sql("""
				INSERT INTO outbox_event
				  (id, aggregate_type, aggregate_id, event_type, actor, payload, occurred_at)
				VALUES (?, 'audit_request', ?, ?, 'worker:assignment', ?::jsonb, now())
				""")
				.params(UUID.randomUUID(), requestId, eventType, payload)
				.update();
	}

	private String createdPayload(AuditRequest request) {
		return json.writeValueAsString(Map.of(
				"requestId", request.id().toString(),
				"clientId", request.clientId().toString(),
				"siteId", request.siteId().toString(),
				"subscriptionLevel", request.subscriptionLevel().name()));
	}
}
