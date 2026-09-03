package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.Audit;
import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import com.qualifyze.audit.persistence.ClientRepository;
import com.qualifyze.audit.persistence.SiteRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Accept an audit request: resolve the client's frozen level, check the site exists and the
 * subscription is active, then persist a {@code PENDING} row with its outbox event (docs/03 §3,
 * ADR 0001). Assignment happens later, off this path.
 *
 * <p>Idempotency is claimed by the {@code audit_request_idempotency} unique index, not a
 * check-then-insert: {@link #execute} tries the write and reads the winner back only when the index
 * rejects it (docs/03 §3). This method is deliberately <em>not</em> {@code @Transactional}: the
 * failed insert's transaction must roll back cleanly before the follow-up read runs.
 */
@Service
public class CreateAuditRequestUseCase {

	private final SiteRepository sites;
	private final ClientRepository clients;
	private final AuditRequestRepository requests;
	private final Clock clock;

	CreateAuditRequestUseCase(SiteRepository sites, ClientRepository clients,
			AuditRequestRepository requests, Clock clock) {
		this.sites = sites;
		this.clients = clients;
		this.requests = requests;
		this.clock = clock;
	}

	public AuditRequest execute(CreateAuditRequestCommand command) {
		ClientSubscription subscription = resolveSubscription(command.clientId());
		requireSiteExists(command.siteId());

		Instant now = clock.instant();
		requireSubscriptionActive(subscription, now, command.clientId());

		AuditRequest request = AuditRequest.accept(UUID.randomUUID(), command.clientId(),
				command.siteId(), subscription.level(), now, Audit.DEFAULT_PROCESSING_DURATION_DAYS,
				command.idempotencyKey());

		return persistOrReplay(request, command);
	}

	private ClientSubscription resolveSubscription(UUID clientId) {
		return clients.findSubscription(clientId)
				.orElseThrow(() -> new UnknownClientException(clientId));
	}

	private void requireSiteExists(UUID siteId) {
		if (!sites.exists(siteId)) {
			throw new SiteNotFoundException(siteId);
		}
	}

	private void requireSubscriptionActive(ClientSubscription subscription, Instant now, UUID clientId) {
		if (!subscription.isActiveOn(now.atZone(ZoneOffset.UTC).toLocalDate())) {
			throw new SubscriptionNotActiveException(clientId);
		}
	}

	private AuditRequest persistOrReplay(AuditRequest request, CreateAuditRequestCommand command) {
		try {
			requests.save(request);
			return request;
		} catch (DuplicateKeyException alreadyClaimed) {
			return replay(command);
		}
	}

	/**
	 * A row already exists for this {@code (client, key)}. A retry of the same intent replays it;
	 * the same key with a different {@code siteId} is a client bug and fails loudly (docs/03 §3).
	 */
	private AuditRequest replay(CreateAuditRequestCommand command) {
		AuditRequest existing = requests
				.findByClientAndIdempotencyKey(command.clientId(), command.idempotencyKey())
				.orElseThrow(() -> new IllegalStateException(
						"idempotency key was claimed but no row is present"));

		if (!existing.siteId().equals(command.siteId())) {
			throw new IdempotencyKeyReusedException(command.clientId());
		}
		return existing;
	}
}
