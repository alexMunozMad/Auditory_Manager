package com.qualifyze.audit.application;

import com.qualifyze.audit.persistence.AuditRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class LeastLoadedAuditorSelectionPolicyTest {

	@Test
	void ranksByLoadOverTheTrailingWindowAnchoredToTheClock() {
		AuditRepository audits = mock(AuditRepository.class);
		Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
		LocalDate expectedWindowStart = LocalDate.parse("2026-06-01")
				.minusDays(LeastLoadedAuditorSelectionPolicy.LOAD_WINDOW_DAYS);

		UUID idle = UUID.randomUUID();
		UUID busy = UUID.randomUUID();
		given(audits.activeAuditorsByLoad(expectedWindowStart)).willReturn(List.of(idle, busy));

		var policy = new LeastLoadedAuditorSelectionPolicy(audits, clock);

		assertEquals(List.of(idle, busy), policy.rankedCandidates());
	}
}
