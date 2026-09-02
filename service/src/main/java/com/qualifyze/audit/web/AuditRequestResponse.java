package com.qualifyze.audit.web;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.DeliveryWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the client sees for one request (docs/03 §3). The frozen {@code earliest/latest_audit_date}
 * columns are projected here into the client's vocabulary — report dates — across the processing
 * duration. The audit itself is never named.
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
		Commitment commitment,
		LocalDate expectedReportDate,
		Object report) {

	/** The contractual promise, in report-date terms (docs/03 §3). {@code reportNoLaterThan} never moves. */
	record Commitment(LocalDate reportNoEarlierThan, LocalDate reportNoLaterThan) {
	}

	static AuditRequestResponse from(AuditRequest request) {
		DeliveryWindow window = request.deliveryWindow();
		int processing = Audit.DEFAULT_PROCESSING_DURATION_DAYS;
		return new AuditRequestResponse(
				request.id(),
				request.siteId(),
				request.status().name(),
				request.subscriptionLevel().name(),
				request.requestedAt(),
				new Commitment(
						window.earliestAuditDate().plusDays(processing),
						window.latestAuditDate().plusDays(processing)),
				request.availableToClientAt(),
				null);
	}
}
