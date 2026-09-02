package com.qualifyze.audit.application;

import java.util.UUID;

/** The caller's identity does not resolve to a known client (the auth stub, 07 §7 → 401). */
public class UnknownClientException extends RuntimeException {

	private final UUID clientId;

	public UnknownClientException(UUID clientId) {
		super("unknown client " + clientId);
		this.clientId = clientId;
	}

	public UUID clientId() {
		return clientId;
	}
}
