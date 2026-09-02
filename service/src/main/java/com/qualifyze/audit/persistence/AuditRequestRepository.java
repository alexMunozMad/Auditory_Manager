package com.qualifyze.audit.persistence;

import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.DeliveryWindow;
import com.qualifyze.audit.domain.RequestStatus;
import com.qualifyze.audit.domain.SubscriptionLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists {@link AuditRequest} rows by hand ({@code JdbcClient}, no JPA — ADR 0004).
 */
@Repository
public class AuditRequestRepository {

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
		return jdbc.sql("""
				SELECT id, client_id, site_id, audit_id, requested_at, subscription_level_code,
				       earliest_audit_date, latest_audit_date, available_to_client_at,
				       status, cancellation_reason, idempotency_key
				  FROM audit_request
				 WHERE client_id = ? AND idempotency_key = ?
				""")
				.params(clientId, idempotencyKey)
				.query((rs, rowNum) -> AuditRequest.rehydrate(
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
						rs.getString("cancellation_reason")))
				.optional();
	}

	private String createdPayload(AuditRequest request) {
		return json.writeValueAsString(Map.of(
				"requestId", request.id().toString(),
				"clientId", request.clientId().toString(),
				"siteId", request.siteId().toString(),
				"subscriptionLevel", request.subscriptionLevel().name()));
	}
}
