package com.qualifyze.audit.domain;

import java.time.LocalDate;

/**
 * What the client is promised, in report-date terms (docs/03 §3): the report arrives no earlier
 * than {@code reportNoEarlierThan} and no later than {@code reportNoLaterThan}. Derived from the
 * frozen delivery window; {@code reportNoLaterThan} is the contractual ceiling and never moves.
 */
public record ReportCommitment(LocalDate reportNoEarlierThan, LocalDate reportNoLaterThan) {
}
