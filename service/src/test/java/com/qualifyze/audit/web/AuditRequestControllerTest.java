package com.qualifyze.audit.web;

import com.qualifyze.audit.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditRequestControllerTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	Clock clock;

	private UUID clientId;
	private UUID siteId;

	@BeforeEach
	void freezeTimeAndSeed() {
		given(clock.instant()).willReturn(NOW);

		UUID supplierId = UUID.randomUUID();
		clientId = UUID.randomUUID();
		siteId = UUID.randomUUID();

		jdbc.sql("INSERT INTO supplier (id, name) VALUES (?, ?)")
				.params(supplierId, "supplier-" + supplierId).update();
		jdbc.sql("INSERT INTO site (id, supplier_id, name) VALUES (?, ?, ?)")
				.params(siteId, supplierId, "site-" + siteId).update();
		insertClient(clientId, "ESSENTIALS", "2027-01-01");
	}

	private void insertClient(UUID id, String level, String validUntil) {
		jdbc.sql("""
				INSERT INTO client (id, name, contact_email, subscription_level_code, subscription_valid_until)
				VALUES (?, ?, ?, ?, CAST(? AS date))
				""")
				.params(id, "client-" + id, "ops@example.com", level, validUntil).update();
	}

	@Test
	void createsTheRequestAndReturns201WithTheProjectedCommitment() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.header("Idempotency-Key", "key-201")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + siteId + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.siteId").value(siteId.toString()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.subscriptionLevel").value("ESSENTIALS"))
				.andExpect(jsonPath("$.requestedAt").value("2026-01-01T10:00:00Z"))
				// ESSENTIALS: report no earlier than requestedAt + 28d, no later than + 120d
				.andExpect(jsonPath("$.commitment.reportNoEarlierThan").value("2026-01-29"))
				.andExpect(jsonPath("$.commitment.reportNoLaterThan").value("2026-05-01"))
				.andExpect(jsonPath("$.expectedReportDate").isEmpty())
				.andExpect(jsonPath("$.report").isEmpty());
	}

	@Test
	void persistsThePendingRowAndItsOutboxEvent() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.header("Idempotency-Key", "key-persist")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + siteId + "\"}"))
				.andExpect(status().isCreated());

		Long rows = jdbc.sql("""
				SELECT count(*) FROM audit_request r
				 JOIN outbox_event e ON e.aggregate_id = r.id AND e.event_type = 'AuditRequestCreated'
				 WHERE r.client_id = ? AND r.site_id = ? AND r.status = 'PENDING'
				""").params(clientId, siteId).query(Long.class).single();
		org.junit.jupiter.api.Assertions.assertEquals(1L, rows);
	}

	@Test
	void returns404ProblemWhenTheSiteIsUnknown() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.header("Idempotency-Key", "key-404")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/site-not-found"));
	}

	@Test
	void returns422ProblemWhenTheSubscriptionHasLapsed() throws Exception {
		UUID lapsed = UUID.randomUUID();
		insertClient(lapsed, "ESSENTIALS", "2025-06-01");

		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", lapsed)
						.header("Idempotency-Key", "key-422")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + siteId + "\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/subscription-not-active"));
	}

	@Test
	void returns401ProblemWhenTheClientIsUnknown() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", UUID.randomUUID())
						.header("Idempotency-Key", "key-401")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + siteId + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/unknown-client"));
	}

	@Test
	void returns400ProblemWhenTheIdempotencyKeyIsMissing() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":\"" + siteId + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/idempotency-key-required"));
	}

	@Test
	void returns400ProblemWhenTheBodyHasNoSiteId() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.header("Idempotency-Key", "key-nobody")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/validation-failed"));
	}

	@Test
	void returns400ProblemWhenTheBodyIsMalformed() throws Exception {
		mockMvc.perform(post("/v1/audit-requests")
						.header("X-Client-Id", clientId)
						.header("Idempotency-Key", "key-malformed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"siteId\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://api.qualifyze.com/problems/validation-failed"));
	}
}
