package com.qualifyze.audit.persistence;

import com.qualifyze.audit.application.ClientSubscription;
import com.qualifyze.audit.domain.SubscriptionLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Read-only access to the {@code client} row (docs/02 §1) — the system does not modify it. */
@Repository
public class ClientRepository {

	private final JdbcClient jdbc;

	ClientRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ClientSubscription> findSubscription(UUID clientId) {
		return jdbc.sql("""
				SELECT subscription_level_code, subscription_valid_until
				  FROM client
				 WHERE id = ?
				""")
				.param(clientId)
				.query((rs, rowNum) -> new ClientSubscription(
						SubscriptionLevel.valueOf(rs.getString("subscription_level_code")),
						rs.getObject("subscription_valid_until", LocalDate.class)))
				.optional();
	}
}
