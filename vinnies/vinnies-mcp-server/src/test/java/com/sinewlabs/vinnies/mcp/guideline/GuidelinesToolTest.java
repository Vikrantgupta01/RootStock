package com.sinewlabs.vinnies.mcp.guideline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GuidelinesToolTest {

	private final AssistanceGuideline energy = new AssistanceGuideline(NeedCategory.ENERGY_BILL,
			"Energy bill assistance", "Pay the retailer directly.", new BigDecimal("400.00"), 90,
			LocalDate.of(2026, 7, 1));
	private final AssistanceGuideline food = new AssistanceGuideline(NeedCategory.FOOD, "Food assistance",
			"Vouchers or a hamper.", new BigDecimal("150.00"), 14, LocalDate.of(2026, 7, 1));

	private final AssistanceGuidelineRepository repository = mock(AssistanceGuidelineRepository.class);
	private final GuidelinesTool tool = new GuidelinesTool(repository);

	@Test
	void theToolReturnsTheRulesNumbersAndPointsAtTheResource() {
		given(repository.findById(NeedCategory.ENERGY_BILL)).willReturn(Optional.of(energy));

		GuidelinesTool.Guideline g = tool.getAssistanceGuidelines(NeedCategory.ENERGY_BILL);

		assertThat(g.limitPerVisitAud()).isEqualByComparingTo("400");
		assertThat(g.repeatWindowDays()).isEqualTo(90);
		assertThat(g.guideline()).isEqualTo("Pay the retailer directly.");
		assertThat(g.resourceUri()).isEqualTo("vinnies://guidelines/ENERGY_BILL");
	}

	@Test
	void theResourceStatesTheSameFactsAsTheTool() {
		given(repository.findById(NeedCategory.ENERGY_BILL)).willReturn(Optional.of(energy));

		String text = tool.guidelineResource("energy_bill");

		assertThat(text).startsWith("# Energy bill assistance")
				.contains("Limit per visit: $400.00", "Repeat window: 90 days", "Effective from: 2026-07-01",
						"Pay the retailer directly.");
	}

	@Test
	void theAllGuidelinesResourceHasEveryTypeInOneDocument() {
		given(repository.findAllByOrderByAssistanceTypeAsc()).willReturn(List.of(energy, food));

		String text = tool.allGuidelinesResource();

		assertThat(text).contains("# Energy bill assistance", "# Food assistance", "---");
	}

	@Test
	void unknownOrMissingTypesAreErrors() {
		assertThatThrownBy(() -> tool.guidelineResource("PHARMACY")).hasMessageContaining("Unknown assistance type");
		assertThatThrownBy(() -> tool.getAssistanceGuidelines(null)).hasMessageContaining("assistanceType");
	}
}
