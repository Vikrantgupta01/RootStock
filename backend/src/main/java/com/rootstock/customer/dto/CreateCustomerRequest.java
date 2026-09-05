package com.rootstock.customer.dto;

import com.rootstock.customer.CustomerStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
		@NotBlank @Size(max = 200) String name,
		@NotBlank @Email @Size(max = 320) String email,
		@Size(max = 200) String company,
		@Size(max = 40) String phone,
		CustomerStatus status,
		@Size(max = 5000) String notes) {
}
