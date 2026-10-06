package com.sinewlabs.vinnies.mcp.assistance;

import com.sinewlabs.vinnies.mcp.household.Household;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Assistance already given to a household (ontology: Assistance extends core.Action). Fictional. */
@Entity
@Table(name = "assistance")
public class Assistance {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String ref;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "household_id", nullable = false)
	private Household household;

	@Column(name = "assisted_on", nullable = false)
	private LocalDate assistedOn;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NeedCategory category;

	@Column(name = "amount_aud", nullable = false, precision = 10, scale = 2)
	private BigDecimal amountAud;

	private String description;

	protected Assistance() {
	}

	public Assistance(UUID id, String ref, Household household, LocalDate assistedOn, NeedCategory category,
			BigDecimal amountAud, String description) {
		this.id = id;
		this.ref = ref;
		this.household = household;
		this.assistedOn = assistedOn;
		this.category = category;
		this.amountAud = amountAud;
		this.description = description;
	}

	public UUID getId() {
		return id;
	}

	public String getRef() {
		return ref;
	}

	public Household getHousehold() {
		return household;
	}

	public LocalDate getAssistedOn() {
		return assistedOn;
	}

	public NeedCategory getCategory() {
		return category;
	}

	public BigDecimal getAmountAud() {
		return amountAud;
	}

	public String getDescription() {
		return description;
	}
}
