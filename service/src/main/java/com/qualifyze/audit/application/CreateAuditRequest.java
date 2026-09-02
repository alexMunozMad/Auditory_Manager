package com.qualifyze.audit.application;

import com.qualifyze.audit.domain.AuditRequest;
import com.qualifyze.audit.persistence.AuditRequestRepository;
import com.qualifyze.audit.persistence.ClientRepository;
import com.qualifyze.audit.persistence.SiteRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Accept an audit request: resolve the client's frozen level, check the site exists and the
 * subscription is active, then persist a {@code PENDING} row with its outbox event (docs/03 §3,
 * ADR 0001). Assignment happens later, off this path.
 */
@Service
public class CreateAuditRequest {

	/** The default the request-time window arithmetic is frozen with (docs/00 A1, docs/02 §3). */
	private static final int PROCESSING_DURATION_DAYS = 7;

	private final SiteRepository sites;
	private final ClientRepository clients;
	private final AuditRequestRepository requests;
	private final Clock clock;

	CreateAuditRequest(SiteRepository sites, ClientRepository clients,
			AuditRequestRepository requests, Clock clock) {
		this.sites = sites;
		this.clients = clients;
		this.requests = requests;
		this.clock = clock;
	}

	public AuditRequest execute(CreateAuditRequestCommand command) {
		ClientSubscription subscription = clients.findSubscription(command.clientId())
				.orElseThrow(() -> new UnknownClientException(command.clientId()));

		if (!sites.exists(command.siteId())) {
			throw new SiteNotFoundException(command.siteId());
		}

		Instant now = clock.instant();
		if (!subscription.isActiveOn(now.atZone(ZoneOffset.UTC).toLocalDate())) {
			throw new SubscriptionNotActiveException(command.clientId());
		}

		AuditRequest request = AuditRequest.accept(UUID.randomUUID(), command.clientId(),
				command.siteId(), subscription.level(), now, PROCESSING_DURATION_DAYS,
				command.idempotencyKey());

		requests.save(request);
		return request;
	}
}
