package com.qualifyze.audit.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Read-only access to the {@code site} catalogue (docs/02 §1). */
@Repository
public class SiteRepository {

	private final JdbcClient jdbc;

	SiteRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public boolean exists(UUID siteId) {
		return jdbc.sql("SELECT exists(SELECT 1 FROM site WHERE id = ?)")
				.param(siteId)
				.query(Boolean.class)
				.single();
	}
}
