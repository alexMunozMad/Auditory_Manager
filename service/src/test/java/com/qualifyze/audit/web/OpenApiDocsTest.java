package com.qualifyze.audit.web;

import com.qualifyze.audit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** springdoc is wired: the generated spec is served and documents the one endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void servesTheOpenApiSpecAndDocumentsTheAuditRequestsEndpoint() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Audit scheduling API"))
				.andExpect(jsonPath("$.paths.['/v1/audit-requests'].post.summary").value("Raise an audit request"))
				.andExpect(jsonPath("$.paths.['/v1/audit-requests'].post.responses.201").exists())
				.andExpect(jsonPath("$.paths.['/v1/audit-requests'].post.responses.422").exists());
	}

	@Test
	void servesTheSwaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}
}
