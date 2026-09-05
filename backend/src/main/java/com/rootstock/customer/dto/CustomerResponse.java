package com.rootstock.customer.dto;

import com.rootstock.customer.Customer;
import com.rootstock.customer.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
		UUID id,
		String name,
		String email,
		String company,
		String phone,
		CustomerStatus status,
		String notes,
		Instant createdAt,
		Instant updatedAt) {

	public static CustomerResponse from(Customer c) {
		return new CustomerResponse(
				c.getId(),
				c.getName(),
				c.getEmail(),
				c.getCompany(),
				c.getPhone(),
				c.getStatus(),
				c.getNotes(),
				c.getCreatedAt(),
				c.getUpdatedAt());
	}
}
