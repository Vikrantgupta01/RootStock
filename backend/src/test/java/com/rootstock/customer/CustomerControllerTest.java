package com.rootstock.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.common.DuplicateResourceException;
import com.rootstock.common.GlobalExceptionHandler;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.customer.dto.CustomerResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerController.class)
@Import(GlobalExceptionHandler.class)
class CustomerControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService service;

	private static CustomerResponse sample(UUID id) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new CustomerResponse(id, "Ada Lovelace", "ada@example.com", "Analytical Engines",
				null, CustomerStatus.ACTIVE, null, now, now);
	}

	@Test
	void createsCustomer() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.create(any())).willReturn(sample(id));

		mockMvc.perform(post("/api/customers")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Ada Lovelace","email":"ada@example.com","company":"Analytical Engines"}"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.containsString(id.toString())))
				.andExpect(jsonPath("$.email").value("ada@example.com"));
	}

	@Test
	void rejectsInvalidEmail() throws Exception {
		mockMvc.perform(post("/api/customers")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Ada","email":"not-an-email"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getReturns404WhenMissing() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.get(eq(id))).willThrow(ResourceNotFoundException.of("Customer", id));

		mockMvc.perform(get("/api/customers/{id}", id))
				.andExpect(status().isNotFound());
	}

	@Test
	void createReturns409OnDuplicateEmail() throws Exception {
		given(service.create(any())).willThrow(new DuplicateResourceException("duplicate"));

		mockMvc.perform(post("/api/customers")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Ada","email":"ada@example.com"}"""))
				.andExpect(status().isConflict());
	}

	@Test
	void listsCustomers() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.list(any())).willReturn(new PageImpl<>(java.util.List.of(sample(id)), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/customers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].email").value("ada@example.com"))
				.andExpect(jsonPath("$.page.totalElements").value(1));
	}

	@Test
	void updatesCustomer() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.update(eq(id), any())).willReturn(sample(id));

		mockMvc.perform(put("/api/customers/{id}", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Ada Lovelace","email":"ada@example.com","status":"ACTIVE"}"""))
				.andExpect(status().isOk());
	}

	@Test
	void deletesCustomer() throws Exception {
		mockMvc.perform(delete("/api/customers/{id}", UUID.randomUUID()))
				.andExpect(status().isNoContent());
	}
}
