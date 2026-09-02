package com.qualifyze.audit.application;

import java.util.List;
import java.util.UUID;

/**
 * How the assignment worker ranks auditors for a new audit. Kept as an interface on purpose
 * (CLAUDE.md rule 7, docs/07 §5): "proportionally" is one reading of A2 among several, and this is
 * the one seam where weighting by audit duration, specialty or region becomes an implementation
 * swap without touching the concurrency design.
 *
 * <p>The default reading — least-loaded over a rolling window, ties broken by id — is
 * {@link LeastLoadedAuditorSelectionPolicy}. The policy only ranks; whether a ranked auditor is
 * free on a given date is scheduling mechanics the worker owns, not policy.
 */
public interface AuditorSelectionPolicy {

	/** Active auditors, best candidate first. */
	List<UUID> rankedCandidates();
}
