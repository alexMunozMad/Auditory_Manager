package com.qualifyze.audit.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tier parameters are a contractual commitment (docs/00 A6). This test is the review gate:
 * changing a delivery plazo means changing a number here and a test breaks, which is exactly
 * the friction A6 wants around that kind of change.
 */
class SubscriptionLevelTest {

	@Test
	void tierParametersAreTheOnesInA6() {
		assertEquals(28, SubscriptionLevel.ESSENTIALS.minWindowDays());
		assertEquals(120, SubscriptionLevel.ESSENTIALS.maxWaitDays());

		assertEquals(21, SubscriptionLevel.ADVANCED.minWindowDays());
		assertEquals(90, SubscriptionLevel.ADVANCED.maxWaitDays());

		assertEquals(0, SubscriptionLevel.PREMIUM.minWindowDays());
		assertEquals(30, SubscriptionLevel.PREMIUM.maxWaitDays());
	}
}
