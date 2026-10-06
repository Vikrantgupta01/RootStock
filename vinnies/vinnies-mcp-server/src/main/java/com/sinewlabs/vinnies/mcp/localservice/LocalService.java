package com.sinewlabs.vinnies.mcp.localservice;

import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A local service households can be referred to (ontology: Service, target of Referral.refersTo). Fictional. */
@Entity
@Table(name = "local_service")
public class LocalService {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String ref;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "need_category", nullable = false)
	private NeedCategory needCategory;

	@Column(nullable = false)
	private String suburb;

	@Column(nullable = false)
	private String postcode;

	@Column(nullable = false)
	private String address;

	private String phone;

	@Column(nullable = false)
	private String hours;

	@Column(nullable = false)
	private String eligibility;

	protected LocalService() {
	}

	public LocalService(UUID id, String ref, String name, NeedCategory needCategory, String suburb, String postcode,
			String address, String phone, String hours, String eligibility) {
		this.id = id;
		this.ref = ref;
		this.name = name;
		this.needCategory = needCategory;
		this.suburb = suburb;
		this.postcode = postcode;
		this.address = address;
		this.phone = phone;
		this.hours = hours;
		this.eligibility = eligibility;
	}

	public UUID getId() {
		return id;
	}

	public String getRef() {
		return ref;
	}

	public String getName() {
		return name;
	}

	public NeedCategory getNeedCategory() {
		return needCategory;
	}

	public String getSuburb() {
		return suburb;
	}

	public String getPostcode() {
		return postcode;
	}

	public String getAddress() {
		return address;
	}

	public String getPhone() {
		return phone;
	}

	public String getHours() {
		return hours;
	}

	public String getEligibility() {
		return eligibility;
	}
}
