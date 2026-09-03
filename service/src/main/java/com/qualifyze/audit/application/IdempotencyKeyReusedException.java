package com.qualifyze.audit.application;

import java.util.UUID;

/**
 * The same {@code Idempotency-Key} came back with a different body (a different {@code siteId}) -
 * a client bug, not a retry (docs/03 §3 → 422 idempotency-key-reused). It must fail loudly rather
 * than serve the first request's response to a second, different intent.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	private final UUID clientId;

	public IdempotencyKeyReusedException(UUID clientId) {
		super("idempotency key reused with a different body by client " + clientId);
		this.clientId = clientId;
	}

	public UUID clientId() {
		return clientId;
	}
}
