package com.rootstock.core.customer.dto;

import com.rootstock.core.customer.CustomerStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of a customer's mutable fields (PUT semantics).
 */
public record UpdateCustomerRequest(
		@NotBlank @Size(max = 200) String name,
		@NotBlank @Email @Size(max = 320) String email,
		@Size(max = 200) String company,
		@Size(max = 40) String phone,
		@NotNull CustomerStatus status,
		@Size(max = 5000) String notes) {
}
