package com.sinewlabs.vinnies.mcp.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A member of a household (ontology: Household.members -> Person). Fictional; names are personal data. */
@Entity
@Table(name = "person")
public class Person {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "household_id", nullable = false)
	private Household household;

	@Column(name = "given_name", nullable = false)
	private String givenName;

	@Column(name = "family_name", nullable = false)
	private String familyName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Relationship relationship;

	// SMALLINT in db/setup.sql; without this Hibernate expects INTEGER and validation fails.
	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "birth_year")
	private Integer birthYear;

	protected Person() {
	}

	Person(UUID id, Household household, String givenName, String familyName, Relationship relationship,
			Integer birthYear) {
		this.id = id;
		this.household = household;
		this.givenName = givenName;
		this.familyName = familyName;
		this.relationship = relationship;
		this.birthYear = birthYear;
	}

	public UUID getId() {
		return id;
	}

	public Household getHousehold() {
		return household;
	}

	public String getGivenName() {
		return givenName;
	}

	public String getFamilyName() {
		return familyName;
	}

	public String fullName() {
		return givenName + " " + familyName;
	}

	public Relationship getRelationship() {
		return relationship;
	}

	public Integer getBirthYear() {
		return birthYear;
	}
}
