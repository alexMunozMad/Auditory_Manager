package com.qualifyze.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class AuditSchedulingApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuditSchedulingApplication.class, args);
	}

	/** One clock, injected everywhere time is read, so tests can freeze it. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
