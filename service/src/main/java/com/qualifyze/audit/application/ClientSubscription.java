package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.SubscriptionLevel;

import java.time.LocalDate;

/**
 * A client's active subscription, as read from the {@code client} row (docs/02 §1). The level is
 * copied onto the request and frozen at acceptance (A6, A9); this record only carries it that far.
 */
public record ClientSubscription(SubscriptionLevel level, LocalDate validUntil) {

	/** Active while {@code subscription_valid_until} has not passed (A9). */
	public boolean isActiveOn(LocalDate date) {
		return !validUntil.isBefore(date);
	}
}
