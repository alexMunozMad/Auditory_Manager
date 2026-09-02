package com.qualifyze.audit.web;

import java.util.UUID;

/**
 * The one field a client sends (docs/03 §3). The client is known from the header, the level from
 * the client, and the dates are a consequence of the level — so there is nothing else to accept.
 */
record CreateAuditRequestBody(UUID siteId) {
}
