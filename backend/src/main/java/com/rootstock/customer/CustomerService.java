package com.rootstock.customer;

import com.rootstock.common.DuplicateResourceException;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.customer.dto.CreateCustomerRequest;
import com.rootstock.customer.dto.CustomerResponse;
import com.rootstock.customer.dto.UpdateCustomerRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerService {

	private final CustomerRepository repository;

	public CustomerService(CustomerRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public Page<CustomerResponse> list(Pageable pageable) {
		return repository.findAll(pageable).map(CustomerResponse::from);
	}

	@Transactional(readOnly = true)
	public CustomerResponse get(UUID id) {
		return CustomerResponse.from(require(id));
	}

	public CustomerResponse create(CreateCustomerRequest request) {
		if (repository.existsByEmailIgnoreCase(request.email())) {
			throw new DuplicateResourceException("A customer with email " + request.email() + " already exists.");
		}
		Customer customer = new Customer(
				request.name(),
				request.email(),
				request.company(),
				request.phone(),
				request.status(),
				request.notes());
		return CustomerResponse.from(repository.save(customer));
	}

	public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
		Customer customer = require(id);
		repository.findByEmailIgnoreCase(request.email())
				.filter(other -> !other.getId().equals(id))
				.ifPresent(other -> {
					throw new DuplicateResourceException(
							"A customer with email " + request.email() + " already exists.");
				});
		customer.setName(request.name());
		customer.setEmail(request.email());
		customer.setCompany(request.company());
		customer.setPhone(request.phone());
		customer.setStatus(request.status());
		customer.setNotes(request.notes());
		// Flush now so @LastModifiedDate runs before the response is built --
		// otherwise updatedAt would still show its pre-update value (JPA only
		// applies auditing at flush/commit time).
		repository.flush();
		return CustomerResponse.from(customer);
	}

	public void delete(UUID id) {
		if (!repository.existsById(id)) {
			throw ResourceNotFoundException.of("Customer", id);
		}
		repository.deleteById(id);
	}

	private Customer require(UUID id) {
		return repository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Customer", id));
	}
}
