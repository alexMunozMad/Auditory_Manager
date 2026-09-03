package com.qualifyze.audit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The interval of admissible audit dates for one request, derived once from the subscription
 * level at acceptance and then frozen (docs/01 §4, docs/02 §3). Everything downstream only asks
 * whether a candidate date falls inside it - so if the "report no earlier than X" rule is ever
 * wrong, there is exactly one place to fix.
 *
 * <pre>
 *   earliestAuditDate = max(requestDate + minWindow - processing, requestDate + 1)
 *   latestAuditDate   =     requestDate + maxWait   - processing
 * </pre>
 *
 * The {@code + 1} floor matters for Premium: its raw earliest (minWindow 0, processing 7) lands
 * a week before the request. No audit is ever scheduled for the request day or earlier. The
 * request date is read in UTC, matching the {@code request_window_starts_in_future} CHECK.
 */
public record DeliveryWindow(LocalDate earliestAuditDate, LocalDate latestAuditDate) {

	public static DeliveryWindow forRequest(SubscriptionLevel level, Instant requestedAt,
			int processingDurationDays) {

		LocalDate requestDate = requestedAt.atZone(ZoneOffset.UTC).toLocalDate();

		LocalDate rawEarliest = requestDate.plusDays(level.minWindowDays() - processingDurationDays);
		LocalDate tomorrow = requestDate.plusDays(1);
		LocalDate earliest = rawEarliest.isAfter(tomorrow) ? rawEarliest : tomorrow;

		LocalDate latest = requestDate.plusDays(level.maxWaitDays() - processingDurationDays);

		return new DeliveryWindow(earliest, latest);
	}

	public boolean contains(LocalDate auditDate) {
		return !auditDate.isBefore(earliestAuditDate) && !auditDate.isAfter(latestAuditDate);
	}
}
