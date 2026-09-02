package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.persistence.AuditRepository;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The assignment decision for one claimed request ({@code diagrams/worker-decision.mermaid}):
 *
 * <ol>
 *   <li>reuse — the site's in-flight audit (index 2, validity projected), then its current published
 *       audit (index 3, real validity); attach if {@link AuditRequest#canReuse} (A7);</li>
 *   <li>otherwise schedule a new audit on the earliest free date in the window held by a
 *       {@link AuditorSelectionPolicy}-ranked auditor, floored at the previous audit's expiry (A7);</li>
 *   <li>a unique-index violation on the insert → the next candidate, in the same transaction
 *       (ADR 0004);</li>
 *   <li>no candidate and the deadline still open → stay {@code PENDING} (A4); deadline passed →
 *       {@code UNSCHEDULABLE} with an event.</li>
 * </ol>
 *
 * Runs inside the worker's transaction; every write here commits with the claim (A8).
 */
@Service
public class AssignmentService {

	private static final int PROCESSING = Audit.DEFAULT_PROCESSING_DURATION_DAYS;

	/** Bounds the retry loop: past this many lost races the request waits for the next tick. */
	static final int MAX_PLACEMENT_ATTEMPTS = 15;

	private final AuditRepository audits;
	private final AuditRequestRepository requests;
	private final AuditorSelectionPolicy auditorPolicy;
	private final Clock clock;

	AssignmentService(AuditRepository audits, AuditRequestRepository requests,
			AuditorSelectionPolicy auditorPolicy, Clock clock) {
		this.audits = audits;
		this.requests = requests;
		this.auditorPolicy = auditorPolicy;
		this.clock = clock;
	}

	public AssignmentOutcome assign(AuditRequest request) {
		Optional<Audit> inFlight = audits.findInFlightBySite(request.siteId());
		if (inFlight.isPresent()) {
			Audit audit = inFlight.get();
			if (request.canReuse(audit.projectedPublicationDate(), audit.projectedValidUntil(), PROCESSING)) {
				return attach(request, audit.id(), audit.projectedPublicationDate(),
						AssignmentOutcome.REUSED_IN_FLIGHT);
			}
			// An in-flight audit exists but publishes too late for this request, and the partial
			// unique index forbids a second one (the A7 known limitation, docs/07 §2). Wait it out.
			return deferOrGiveUp(request);
		}

		Optional<Audit> published = audits.findCurrentPublishedBySite(request.siteId());
		LocalDate placementFloor = request.deliveryWindow().earliestAuditDate();
		if (published.isPresent()) {
			Audit audit = published.get();
			if (request.canReuse(audit.publishedAt(), audit.validUntil(), PROCESSING)) {
				return attach(request, audit.id(), audit.publishedAt(), AssignmentOutcome.REUSED_PUBLISHED);
			}
			// Not reusable: a new audit for this site cannot publish until the day after the old one
			// expires, so its audit date is floored accordingly (A7 non-overlap).
			LocalDate flooredAuditDate = audit.validUntil().plusDays(1).minusDays(PROCESSING);
			if (flooredAuditDate.isAfter(placementFloor)) {
				placementFloor = flooredAuditDate;
			}
		}

		Optional<Audit> placed = place(request, placementFloor);
		if (placed.isPresent()) {
			return attach(request, placed.get().id(), placed.get().projectedPublicationDate(),
					AssignmentOutcome.SCHEDULED_NEW);
		}
		return deferOrGiveUp(request);
	}

	private Optional<Audit> place(AuditRequest request, LocalDate floor) {
		LocalDate latest = request.deliveryWindow().latestAuditDate();
		if (floor.isAfter(latest)) {
			return Optional.empty();
		}

		List<UUID> ranked = auditorPolicy.rankedCandidates();
		if (ranked.isEmpty()) {
			return Optional.empty();
		}

		Map<LocalDate, Set<UUID>> booked = audits.auditorsBookedBetween(floor, latest);
		Set<Slot> lostRaces = new HashSet<>();

		for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
			Optional<Slot> slot = earliestFreeSlot(floor, latest, ranked, booked, lostRaces);
			if (slot.isEmpty()) {
				return Optional.empty();
			}
			Audit audit = Audit.schedule(UUID.randomUUID(), request.siteId(),
					slot.get().auditorId(), slot.get().date(), PROCESSING);
			try {
				audits.save(audit);
				return Optional.of(audit);
			} catch (DuplicateKeyException lostRace) {
				lostRaces.add(slot.get());
			}
		}
		return Optional.empty();
	}

	private static Optional<Slot> earliestFreeSlot(LocalDate floor, LocalDate latest, List<UUID> ranked,
			Map<LocalDate, Set<UUID>> booked, Set<Slot> lostRaces) {

		for (LocalDate day = floor; !day.isAfter(latest); day = day.plusDays(1)) {
			Set<UUID> taken = booked.getOrDefault(day, Set.of());
			for (UUID auditor : ranked) {
				Slot slot = new Slot(day, auditor);
				if (!taken.contains(auditor) && !lostRaces.contains(slot)) {
					return Optional.of(slot);
				}
			}
		}
		return Optional.empty();
	}

	private AssignmentOutcome attach(AuditRequest request, UUID auditId, LocalDate publicationDate,
			AssignmentOutcome outcome) {

		request.attachTo(auditId, request.accessDateFor(publicationDate));
		requests.update(request);
		requests.recordScheduled(request);
		return outcome;
	}

	private AssignmentOutcome deferOrGiveUp(AuditRequest request) {
		if (LocalDate.now(clock).isAfter(request.deliveryWindow().latestAuditDate())) {
			request.markUnschedulable();
			requests.update(request);
			requests.recordUnschedulable(request);
			return AssignmentOutcome.UNSCHEDULABLE;
		}
		return AssignmentOutcome.DEFERRED;
	}

	private record Slot(LocalDate date, UUID auditorId) {
	}
}
