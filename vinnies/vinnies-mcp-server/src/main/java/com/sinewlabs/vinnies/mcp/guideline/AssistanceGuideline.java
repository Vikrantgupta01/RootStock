package com.sinewlabs.vinnies.mcp.guideline;

import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The client's guideline for one kind of assistance: the text reviewers read,
 * plus the two numbers the pack's rules use. Fictional content.
 */
@Entity
@Table(name = "assistance_guideline")
public class AssistanceGuideline {

	@Id
	@Enumerated(EnumType.STRING)
	@Column(name = "assistance_type")
	private NeedCategory assistanceType;

	@Column(nullable = false)
	private String title;

	@Column(name = "guideline_text", nullable = false)
	private String guidelineText;

	@Column(name = "limit_per_visit_aud", nullable = false, precision = 10, scale = 2)
	private BigDecimal limitPerVisitAud;

	@Column(name = "repeat_window_days", nullable = false)
	private int repeatWindowDays;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	protected AssistanceGuideline() {
	}

	public AssistanceGuideline(NeedCategory assistanceType, String title, String guidelineText,
			BigDecimal limitPerVisitAud, int repeatWindowDays, LocalDate effectiveFrom) {
		this.assistanceType = assistanceType;
		this.title = title;
		this.guidelineText = guidelineText;
		this.limitPerVisitAud = limitPerVisitAud;
		this.repeatWindowDays = repeatWindowDays;
		this.effectiveFrom = effectiveFrom;
	}

	public NeedCategory getAssistanceType() {
		return assistanceType;
	}

	public String getTitle() {
		return title;
	}

	public String getGuidelineText() {
		return guidelineText;
	}

	public BigDecimal getLimitPerVisitAud() {
		return limitPerVisitAud;
	}

	public int getRepeatWindowDays() {
		return repeatWindowDays;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}
}
