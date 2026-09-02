package com.qualifyze.audit.application;

import java.util.UUID;

/** The client's subscription_valid_until has passed (A9; docs/03 §3 → 422 subscription-not-active). */
public class SubscriptionNotActiveException extends RuntimeException {

	private final UUID clientId;

	public SubscriptionNotActiveException(UUID clientId) {
		super("the subscription for client " + clientId + " is not active");
		this.clientId = clientId;
	}

	public UUID clientId() {
		return clientId;
	}
}
