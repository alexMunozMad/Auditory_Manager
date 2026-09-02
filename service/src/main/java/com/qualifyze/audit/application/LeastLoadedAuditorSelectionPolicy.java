package com.qualifyze.audit.application;

import com.qualifyze.audit.persistence.AuditRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The default reading of A2 (docs/05 §5): the least-loaded eligible auditor over a rolling window,
 * ties broken by id for reproducibility. Load is a {@code COUNT} of non-discarded audits in the
 * window — never a stored counter, because a lifetime total cannot express a rolling window
 * (docs/02 §7).
 */
@Component
public class LeastLoadedAuditorSelectionPolicy implements AuditorSelectionPolicy {

	/** Fairness is measured over audits within this trailing window. */
	static final int LOAD_WINDOW_DAYS = 90;

	private final AuditRepository audits;
	private final Clock clock;

	LeastLoadedAuditorSelectionPolicy(AuditRepository audits, Clock clock) {
		this.audits = audits;
		this.clock = clock;
	}

	@Override
	public List<UUID> rankedCandidates() {
		LocalDate windowStart = LocalDate.now(clock).minusDays(LOAD_WINDOW_DAYS);
		return audits.activeAuditorsByLoad(windowStart);
	}
}
