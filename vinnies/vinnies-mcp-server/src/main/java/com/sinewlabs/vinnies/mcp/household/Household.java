package com.sinewlabs.vinnies.mcp.household;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A household the Vinnies conference supports (ontology: Household extends
 * core.Party). Fictional. {@code phone} and the members' names are personal
 * data: tools return them only when a step needs them.
 */
@Entity
@Table(name = "household")
public class Household {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String ref;

	@Column(name = "family_name", nullable = false)
	private String familyName;

	@Column(nullable = false)
	private String suburb;

	@Column(nullable = false)
	private String postcode;

	private String phone;

	@Column(name = "consent_given", nullable = false)
	private boolean consentGiven;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Person> members = new ArrayList<>();

	protected Household() {
	}

	public Household(UUID id, String ref, String familyName, String suburb, String postcode, String phone,
			boolean consentGiven, Instant createdAt) {
		this.id = id;
		this.ref = ref;
		this.familyName = familyName;
		this.suburb = suburb;
		this.postcode = postcode;
		this.phone = phone;
		this.consentGiven = consentGiven;
		this.createdAt = createdAt;
	}

	/** Adds a member, keeping both sides of the relation in step. */
	public Person addMember(UUID personId, String givenName, String familyName, Relationship relationship,
			Integer birthYear) {
		Person person = new Person(personId, this, givenName, familyName, relationship, birthYear);
		members.add(person);
		return person;
	}

	public UUID getId() {
		return id;
	}

	public String getRef() {
		return ref;
	}

	public String getFamilyName() {
		return familyName;
	}

	public String getSuburb() {
		return suburb;
	}

	public String getPostcode() {
		return postcode;
	}

	public String getPhone() {
		return phone;
	}

	public boolean isConsentGiven() {
		return consentGiven;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<Person> getMembers() {
		return members;
	}
}
