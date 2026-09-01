package com.qualifyze.audit;

import org.springframework.boot.SpringApplication;

public class TestAuditSchedulingApplication {

	public static void main(String[] args) {
		SpringApplication.from(AuditSchedulingApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
