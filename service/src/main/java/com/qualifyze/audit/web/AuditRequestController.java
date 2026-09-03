package com.qualifyze.audit.web;

import com.qualifyze.audit.application.CreateAuditRequest;
import com.qualifyze.audit.application.CreateAuditRequestCommand;
import com.qualifyze.audit.domain.AuditRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /v1/audit-requests} (docs/03 §3). The controller only translates HTTP: it lifts the
 * caller and the idempotency key out of the headers, hands the use case a {@link CreateAuditRequestCommand},
 * and projects the resulting aggregate into the client's response shape. Every business rule and the
 * transaction boundary live in {@link CreateAuditRequest}; error translation lives in
 * {@link ApiExceptionHandler}.
 *
 * <p>{@code X-Client-Id} stands in for the JWT subject - the auth stub, one seam (docs/07 §7).
 */
@RestController
@RequestMapping("/v1/audit-requests")
class AuditRequestController {

	private final CreateAuditRequest createAuditRequest;

	AuditRequestController(CreateAuditRequest createAuditRequest) {
		this.createAuditRequest = createAuditRequest;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AuditRequestResponse create(
			@RequestHeader("X-Client-Id") UUID clientId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestBody(required = false) CreateAuditRequestBody body) {

		if (body == null || body.siteId() == null) {
			throw new IllegalArgumentException("siteId is required");
		}

		AuditRequest request = createAuditRequest.execute(
				new CreateAuditRequestCommand(clientId, body.siteId(), idempotencyKey));

		return AuditRequestResponse.from(request);
	}
}
