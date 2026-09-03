package com.qualifyze.audit.web;

import com.qualifyze.audit.application.CreateAuditRequestCommand;
import com.qualifyze.audit.application.CreateAuditRequestUseCase;
import com.qualifyze.audit.domain.AuditRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * transaction boundary live in {@link CreateAuditRequestUseCase}; error translation lives in
 * {@link ApiExceptionHandler}.
 *
 * <p>{@code X-Client-Id} stands in for the JWT subject: the auth stub, one seam (docs/07 §7).
 */
@RestController
@RequestMapping("/v1/audit-requests")
@Tag(name = "Audit requests", description = "Raise an audit request and read back its commitment")
class AuditRequestController {

	private final CreateAuditRequestUseCase createAuditRequest;

	AuditRequestController(CreateAuditRequestUseCase createAuditRequest) {
		this.createAuditRequest = createAuditRequest;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Raise an audit request",
			description = "Persists the request as PENDING and returns 201 with the projected report "
					+ "commitment. Assignment to an auditor happens asynchronously (ADR 0001). "
					+ "Idempotent on the Idempotency-Key header.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Created - status PENDING"),
			@ApiResponse(responseCode = "400", description = "Malformed body, or the Idempotency-Key header is missing (problem+json)"),
			@ApiResponse(responseCode = "401", description = "X-Client-Id does not resolve to a known client"),
			@ApiResponse(responseCode = "404", description = "No such site"),
			@ApiResponse(responseCode = "422", description = "Subscription not active, or the key was reused with a different body")
	})
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
