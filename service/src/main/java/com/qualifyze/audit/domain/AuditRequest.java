package com.qualifyze.audit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * A client's commitment: "audit this site under my subscription terms" (docs/01).
 *
 * <p>The subscription level and the delivery window are resolved once, at acceptance, and never
 * recomputed (A6, A9) — they are the contract. The status machine (docs/01 §5) advances
 * independently of the audit's own, and every transition starts from a named state, never a
 * wildcard (A5): the methods below reject a move that does not begin where it should.
 */
public class AuditRequest {

	private final UUID id;
	private final UUID clientId;
	private final UUID siteId;
	private final SubscriptionLevel subscriptionLevel;
	private final Instant requestedAt;
	private final DeliveryWindow deliveryWindow;
	private final String idempotencyKey;

	private RequestStatus status;
	private UUID auditId;
	private LocalDate availableToClientAt;
	private String cancellationReason;

	private AuditRequest(UUID id, UUID clientId, UUID siteId, SubscriptionLevel subscriptionLevel,
			Instant requestedAt, DeliveryWindow deliveryWindow, String idempotencyKey) {
		this.id = id;
		this.clientId = clientId;
		this.siteId = siteId;
		this.subscriptionLevel = subscriptionLevel;
		this.requestedAt = requestedAt;
		this.deliveryWindow = deliveryWindow;
		this.idempotencyKey = idempotencyKey;
		this.status = RequestStatus.PENDING;
	}

	/**
	 * Accept a request: freeze the level and the delivery window, land it as {@link RequestStatus#PENDING}.
	 * The row is the queue (ADR 0001) until the assignment worker places it.
	 */
	public static AuditRequest accept(UUID id, UUID clientId, UUID siteId, SubscriptionLevel level,
			Instant requestedAt, int processingDurationDays, String idempotencyKey) {

		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(clientId, "clientId");
		Objects.requireNonNull(siteId, "siteId");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(requestedAt, "requestedAt");
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}

		DeliveryWindow window = DeliveryWindow.forRequest(level, requestedAt, processingDurationDays);
		return new AuditRequest(id, clientId, siteId, level, requestedAt, window, idempotencyKey);
	}

	/**
	 * Rebuild an aggregate from its persisted row. Persistence-facing — the caller is a repository
	 * and every field is taken as stored, with no invariant re-run: the row already satisfied them
	 * on the way in (the CHECKs in docs/02 §3). A rehydrated request equals the one that was saved.
	 */
	public static AuditRequest rehydrate(UUID id, UUID clientId, UUID siteId, SubscriptionLevel level,
			Instant requestedAt, DeliveryWindow deliveryWindow, String idempotencyKey,
			RequestStatus status, UUID auditId, LocalDate availableToClientAt, String cancellationReason) {

		AuditRequest request = new AuditRequest(id, clientId, siteId, level, requestedAt,
				deliveryWindow, idempotencyKey);
		request.status = status;
		request.auditId = auditId;
		request.availableToClientAt = availableToClientAt;
		request.cancellationReason = cancellationReason;
		return request;
	}

	/** PENDING → SCHEDULED: bound to an audit (newly scheduled or reused), with this client's access date. */
	public void attachTo(UUID auditId, LocalDate availableToClientAt) {
		requireStatus(RequestStatus.PENDING, "attach");
		this.auditId = Objects.requireNonNull(auditId, "auditId");
		this.availableToClientAt = Objects.requireNonNull(availableToClientAt, "availableToClientAt");
		this.status = RequestStatus.SCHEDULED;
	}

	/** PENDING → UNSCHEDULABLE: the deadline passed with no placement. Terminal; the caller emits the event. */
	public void markUnschedulable() {
		requireStatus(RequestStatus.PENDING, "mark unschedulable");
		this.status = RequestStatus.UNSCHEDULABLE;
	}

	/** SCHEDULED → FULFILLED: the audit has published and this client's access date has passed (docs/01 §5). */
	public void fulfil() {
		requireStatus(RequestStatus.SCHEDULED, "fulfil");
		this.status = RequestStatus.FULFILLED;
	}

	/**
	 * PENDING or SCHEDULED → CANCELLED, with a reason: a regulated domain records why a commitment
	 * was withdrawn (docs/02 §3). FULFILLED cannot be cancelled — once the report is available there
	 * is nothing left to withdraw.
	 */
	public void cancel(String reason) {
		if (status != RequestStatus.PENDING && status != RequestStatus.SCHEDULED) {
			throw new IllegalStateException("cannot cancel a request that is " + status);
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("a cancellation reason is required");
		}
		this.cancellationReason = reason;
		this.status = RequestStatus.CANCELLED;
	}

	private void requireStatus(RequestStatus required, String action) {
		if (status != required) {
			throw new IllegalStateException("cannot " + action + " a request that is " + status);
		}
	}

	/**
	 * The frozen window projected into the client's vocabulary — report dates — across the processing
	 * duration (docs/03 §3). {@code reportNoLaterThan} is the contractual ceiling.
	 */
	public ReportCommitment reportCommitment(int processingDurationDays) {
		return new ReportCommitment(
				deliveryWindow.earliestAuditDate().plusDays(processingDurationDays),
				deliveryWindow.latestAuditDate().plusDays(processingDurationDays));
	}

	/**
	 * When this client could read the report if the audit publishes on {@code publicationDate}:
	 * never before the client's own minimum window (A7).
	 * <pre>available_to_client_at = max(publicationDate, requested_at + min_window)</pre>
	 */
	public LocalDate accessDateFor(LocalDate publicationDate) {
		LocalDate windowFloor = requestedAt.atZone(ZoneOffset.UTC).toLocalDate()
				.plusDays(subscriptionLevel.minWindowDays());
		return publicationDate.isAfter(windowFloor) ? publicationDate : windowFloor;
	}

	/**
	 * Whether this request may attach to an audit that publishes on {@code publicationDate} and stays
	 * valid until {@code validUntil} — both A7 conditions (docs/00 A7, docs/01 §3):
	 * <pre>
	 *   accessDate ≤ validUntil            the audit is still valid for this client
	 *   accessDate ≤ reportNoLaterThan     the contractual ceiling is still met
	 * </pre>
	 */
	public boolean canReuse(LocalDate publicationDate, LocalDate validUntil, int processingDurationDays) {
		LocalDate accessDate = accessDateFor(publicationDate);
		return !accessDate.isAfter(validUntil)
				&& !accessDate.isAfter(reportCommitment(processingDurationDays).reportNoLaterThan());
	}

	public UUID id() {
		return id;
	}

	public UUID clientId() {
		return clientId;
	}

	public UUID siteId() {
		return siteId;
	}

	public SubscriptionLevel subscriptionLevel() {
		return subscriptionLevel;
	}

	public Instant requestedAt() {
		return requestedAt;
	}

	public DeliveryWindow deliveryWindow() {
		return deliveryWindow;
	}

	public String idempotencyKey() {
		return idempotencyKey;
	}

	public RequestStatus status() {
		return status;
	}

	public UUID auditId() {
		return auditId;
	}

	public LocalDate availableToClientAt() {
		return availableToClientAt;
	}

	public String cancellationReason() {
		return cancellationReason;
	}
}
