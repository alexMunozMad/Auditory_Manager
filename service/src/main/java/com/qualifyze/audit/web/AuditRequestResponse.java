package com.qualifyze.audit.web;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.ReportCommitment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the client sees for one request (docs/03 §3). The frozen {@code earliest/latest_audit_date}
 * columns are projected into the client's vocabulary — report dates — by
 * {@link AuditRequest#reportCommitment(int)}. The audit itself is never named.
 *
 * @param expectedReportDate {@code available_to_client_at} once a slot exists; {@code null} while PENDING.
 * @param report             the report projection once FULFILLED; {@code null} otherwise. Populated
 *                           by the GET endpoint, which is out of this slice (docs/03 §4).
 */
record AuditRequestResponse(
		UUID id,
		UUID siteId,
		String status,
		String subscriptionLevel,
		Instant requestedAt,
		ReportCommitment commitment,
		LocalDate expectedReportDate,
		Object report) {

	static AuditRequestResponse from(AuditRequest request) {
		return new AuditRequestResponse(
				request.id(),
				request.siteId(),
				request.status().name(),
				request.subscriptionLevel().name(),
				request.requestedAt(),
				request.reportCommitment(Audit.DEFAULT_PROCESSING_DURATION_DAYS),
				request.availableToClientAt(),
				null);
	}
}
