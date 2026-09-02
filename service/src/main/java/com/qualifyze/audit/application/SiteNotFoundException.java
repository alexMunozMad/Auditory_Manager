package com.qualifyze.audit.application;

import java.util.UUID;

/** No such site (docs/03 §3 → 404 site-not-found). */
public class SiteNotFoundException extends RuntimeException {

	private final UUID siteId;

	public SiteNotFoundException(UUID siteId) {
		super("no site with id " + siteId);
		this.siteId = siteId;
	}

	public UUID siteId() {
		return siteId;
	}
}
