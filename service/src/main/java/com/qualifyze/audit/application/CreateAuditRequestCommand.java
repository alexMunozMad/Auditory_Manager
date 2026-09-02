package com.qualifyze.audit.application;

import java.util.UUID;

/**
 * The single field a client sends ({@code siteId}) plus what the edge resolves for it: the client
 * identity (from the token — auth is a stub, 07 §7) and the idempotency key (from the header).
 */
public record CreateAuditRequestCommand(UUID clientId, UUID siteId, String idempotencyKey) {
}
