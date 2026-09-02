package com.qualifyze.audit.web;

import com.qualifyze.audit.application.SiteNotFoundException;
import com.qualifyze.audit.application.SubscriptionNotActiveException;
import com.qualifyze.audit.application.UnknownClientException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Domain and application exceptions translated to RFC 9457 {@code application/problem+json}
 * (docs/03 §8). The {@code type} URI is the stable part of the contract clients branch on; the
 * {@code title} and {@code detail} text are free to change.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's own request faults —
 * a missing header, an unreadable body — are shaped the same way, not left as Spring's default.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final String TYPE_BASE = "https://api.qualifyze.com/problems/";

	@ExceptionHandler(UnknownClientException.class)
	ProblemDetail onUnknownClient(UnknownClientException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "unknown-client",
				"Client not recognised", ex.getMessage());
	}

	@ExceptionHandler(SiteNotFoundException.class)
	ProblemDetail onSiteNotFound(SiteNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "site-not-found",
				"Site not found", ex.getMessage());
	}

	@ExceptionHandler(SubscriptionNotActiveException.class)
	ProblemDetail onSubscriptionNotActive(SubscriptionNotActiveException ex) {
		return problem(HttpStatus.UNPROCESSABLE_ENTITY, "subscription-not-active",
				"Subscription is not active", ex.getMessage());
	}

	/** A malformed value the domain rejected at the boundary (e.g. a blank idempotency key). */
	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail onIllegalArgument(IllegalArgumentException ex) {
		return problem(HttpStatus.BAD_REQUEST, "validation-failed",
				"Request is not valid", ex.getMessage());
	}

	@Override
	protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		String slug = "validation-failed";
		String title = "Request is not valid";
		if (ex instanceof MissingRequestHeaderException missing
				&& "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName())) {
			slug = "idempotency-key-required";
			title = "Idempotency-Key header is required";
		}
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, slug, title, ex.getMessage());
		return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation-failed",
				"Request body is not valid", "The request body is missing or malformed.");
		return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
	}

	private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setType(URI.create(TYPE_BASE + slug));
		body.setTitle(title);
		return body;
	}
}
