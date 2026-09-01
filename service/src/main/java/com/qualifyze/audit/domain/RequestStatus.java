package com.qualifyze.audit.domain;

/**
 * The audit-request lifecycle (docs/01 §5). Advances independently of the audit's own machine.
 * These names are binding (CLAUDE.md rule 11) and match {@code request_status_valid} in docs/02.
 */
public enum RequestStatus {
	PENDING,
	SCHEDULED,
	FULFILLED,
	UNSCHEDULABLE,
	CANCELLED
}
