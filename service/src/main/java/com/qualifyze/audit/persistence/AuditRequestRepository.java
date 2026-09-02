package com.qualifyze.audit.persistence;

import com.qualifyze.audit.domain.AuditRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.Map;
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

	private String createdPayload(AuditRequest request) {
		return json.writeValueAsString(Map.of(
				"requestId", request.id().toString(),
				"clientId", request.clientId().toString(),
				"siteId", request.siteId().toString(),
				"subscriptionLevel", request.subscriptionLevel().name()));
	}
}
