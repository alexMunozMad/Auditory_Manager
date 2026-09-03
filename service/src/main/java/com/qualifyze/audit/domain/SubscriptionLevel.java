package com.qualifyze.audit.domain;

/**
 * The subscription tiers and their delivery terms (docs/00 A6). The parameters live here as
 * code, not as table rows: changing a contractual plazo should cost a commit and a review, not
 * an {@code UPDATE} in production. {@code SubscriptionLevelTest} is that review gate.
 *
 * <p>When levels are negotiated per client the terms become data and this enum becomes the set
 * of defaults - not built (A6).
 */
public enum SubscriptionLevel {

	ESSENTIALS(28, 120),
	ADVANCED(21, 90),
	PREMIUM(0, 30);

	private final int minWindowDays;
	private final int maxWaitDays;

	SubscriptionLevel(int minWindowDays, int maxWaitDays) {
		this.minWindowDays = minWindowDays;
		this.maxWaitDays = maxWaitDays;
	}

	/** Days from acceptance before the client may read the report ("report no earlier than"). */
	public int minWindowDays() {
		return minWindowDays;
	}

	/** Days from acceptance by which the report must reach the client (the contractual ceiling). */
	public int maxWaitDays() {
		return maxWaitDays;
	}
}
