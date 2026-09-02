package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.domain.AuditStatus;
import com.qualifyze.audit.domain.RequestStatus;
import com.qualifyze.audit.domain.SubscriptionLevel;
import com.qualifyze.audit.persistence.AuditRepository;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AssignmentServiceTest {

	private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T10:00:00Z");
	// ESSENTIALS window: 2026-01-22 .. 2026-04-24; reportNoLaterThan 2026-05-01.
	private static final LocalDate WINDOW_EARLIEST = LocalDate.parse("2026-01-22");

	private final AuditRepository audits = mock(AuditRepository.class);
	private final AuditRequestRepository requests = mock(AuditRequestRepository.class);
	private final AuditorSelectionPolicy policy = mock(AuditorSelectionPolicy.class);

	private final UUID siteId = UUID.randomUUID();

	private AssignmentService serviceAt(String today) {
		Clock clock = Clock.fixed(Instant.parse(today + "T00:00:00Z"), ZoneOffset.UTC);
		return new AssignmentService(audits, requests, policy, clock);
	}

	private AuditRequest pendingRequest() {
		return AuditRequest.accept(UUID.randomUUID(), UUID.randomUUID(), siteId,
				SubscriptionLevel.ESSENTIALS, REQUESTED_AT, 7, "key");
	}

	@Test
	void reusesTheInFlightAuditWhenBothA7ConditionsHold() {
		UUID auditId = UUID.randomUUID();
		Audit inFlight = Audit.schedule(auditId, siteId, UUID.randomUUID(), LocalDate.parse("2026-02-10"), 7);
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.of(inFlight));
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-02-01").assign(request);

		assertEquals(AssignmentOutcome.REUSED_IN_FLIGHT, outcome);
		assertEquals(RequestStatus.SCHEDULED, request.status());
		assertEquals(auditId, request.auditId());
		assertEquals(LocalDate.parse("2026-02-17"), request.availableToClientAt()); // audit_date + 7
		verify(requests).update(request);
		verify(requests).recordScheduled(request);
	}

	@Test
	void reusesThePublishedAuditWhenNoInFlightOneExists() {
		UUID auditId = UUID.randomUUID();
		Audit published = Audit.rehydrate(auditId, siteId, UUID.randomUUID(), LocalDate.parse("2026-01-05"),
				1, 7, AuditStatus.PUBLISHED, LocalDate.parse("2026-01-12"), LocalDate.parse("2027-01-12"),
				"s3://reports/x.pdf");
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.empty());
		given(audits.findCurrentPublishedBySite(siteId)).willReturn(Optional.of(published));
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-02-01").assign(request);

		assertEquals(AssignmentOutcome.REUSED_PUBLISHED, outcome);
		assertEquals(auditId, request.auditId());
		assertEquals(LocalDate.parse("2026-01-29"), request.availableToClientAt()); // floored at min window
	}

	@Test
	void schedulesANewAuditOnTheEarliestFreeDateWithTheTopRankedAuditor() {
		UUID auditor = UUID.randomUUID();
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.empty());
		given(audits.findCurrentPublishedBySite(siteId)).willReturn(Optional.empty());
		given(policy.rankedCandidates()).willReturn(List.of(auditor));
		given(audits.auditorsBookedBetween(any(), any())).willReturn(Map.of());
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-01-15").assign(request);

		assertEquals(AssignmentOutcome.SCHEDULED_NEW, outcome);
		ArgumentCaptor<Audit> saved = ArgumentCaptor.forClass(Audit.class);
		verify(audits).save(saved.capture());
		assertEquals(WINDOW_EARLIEST, saved.getValue().auditDate());
		assertEquals(auditor, saved.getValue().auditorId());
		assertEquals(RequestStatus.SCHEDULED, request.status());
	}

	@Test
	void triesTheNextAuditorWhenTheInsertLosesTheUniqueRace() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.empty());
		given(audits.findCurrentPublishedBySite(siteId)).willReturn(Optional.empty());
		given(policy.rankedCandidates()).willReturn(List.of(first, second));
		given(audits.auditorsBookedBetween(any(), any())).willReturn(Map.of());
		doThrow(new DuplicateKeyException("(auditor_id, audit_date)")).doNothing().when(audits).save(any());

		AssignmentOutcome outcome = serviceAt("2026-01-15").assign(pendingRequest());

		assertEquals(AssignmentOutcome.SCHEDULED_NEW, outcome);
		ArgumentCaptor<Audit> saved = ArgumentCaptor.forClass(Audit.class);
		verify(audits, times(2)).save(saved.capture());
		assertEquals(second, saved.getAllValues().get(1).auditorId());
		assertEquals(WINDOW_EARLIEST, saved.getAllValues().get(1).auditDate());
	}

	@Test
	void leavesTheRequestPendingWhenNoAuditorIsAvailableAndTheDeadlineIsOpen() {
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.empty());
		given(audits.findCurrentPublishedBySite(siteId)).willReturn(Optional.empty());
		given(policy.rankedCandidates()).willReturn(List.of());
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-02-01").assign(request);

		assertEquals(AssignmentOutcome.DEFERRED, outcome);
		assertEquals(RequestStatus.PENDING, request.status());
		verify(requests, never()).update(any());
	}

	@Test
	void marksUnschedulableWhenTheDeadlineHasPassedWithNoPlacement() {
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.empty());
		given(audits.findCurrentPublishedBySite(siteId)).willReturn(Optional.empty());
		given(policy.rankedCandidates()).willReturn(List.of());
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-05-01").assign(request); // past latest_audit_date 2026-04-24

		assertEquals(AssignmentOutcome.UNSCHEDULABLE, outcome);
		assertEquals(RequestStatus.UNSCHEDULABLE, request.status());
		verify(requests).update(request);
		verify(requests).recordUnschedulable(request);
	}

	@Test
	void doesNotScheduleASecondAuditWhenAnInFlightOnePublishesTooLate() {
		Audit tooLate = Audit.schedule(UUID.randomUUID(), siteId, UUID.randomUUID(),
				LocalDate.parse("2026-06-01"), 7); // publishes 2026-06-08, past reportNoLaterThan 2026-05-01
		given(audits.findInFlightBySite(siteId)).willReturn(Optional.of(tooLate));
		AuditRequest request = pendingRequest();

		AssignmentOutcome outcome = serviceAt("2026-02-01").assign(request);

		assertEquals(AssignmentOutcome.DEFERRED, outcome);
		assertEquals(RequestStatus.PENDING, request.status());
		verify(audits, never()).auditorsBookedBetween(any(), any());
	}
}
