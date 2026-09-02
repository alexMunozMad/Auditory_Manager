package com.qualifyze.audit.application;

/**
 * What the worker did with a claimed request (docs/05 §4, {@code diagrams/worker-decision.mermaid}).
 * Returned for observability and tests; the state change and its outbox row are already persisted.
 */
public enum AssignmentOutcome {

	/** Attached to the site's in-flight audit — projected validity met both A7 conditions. */
	REUSED_IN_FLIGHT,

	/** Attached to the site's current published audit. */
	REUSED_PUBLISHED,

	/** A new audit was scheduled and the request attached to it. */
	SCHEDULED_NEW,

	/** No slot yet; the request stays {@code PENDING} and awaits a capacity event (A4). */
	DEFERRED,

	/** The deadline passed with no placement: the request is {@code UNSCHEDULABLE}. */
	UNSCHEDULABLE
}
