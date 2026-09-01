package com.qualifyze.audit.domain;

/**
 * The audit lifecycle (docs/01 §5). Advances independently of the request's own machine.
 * These names are binding (CLAUDE.md rule 11) and match {@code audit_status_valid} in docs/02.
 * There is no {@code CANCELLED}: nobody cancels an audit — a client cancels their request and the
 * audit, left without demand, is {@code DISCARDED}.
 */
public enum AuditStatus {
	SCHEDULED,
	IN_PROGRESS,
	PUBLISHED,
	DISCARDED
}
