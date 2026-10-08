package com.sinewlabs.vinnies.mcp.assistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sinewlabs.vinnies.mcp.ClockConfig;
import com.sinewlabs.vinnies.mcp.household.Household;
import com.sinewlabs.vinnies.mcp.household.HouseholdRepository;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistanceHistoryToolTest {

	// 2026-10-06 09:00 in Sydney, which is still 2026-10-05 in UTC: the window must use the Sydney date.
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T22:00:00Z"), ClockConfig.SYDNEY);

	private final HouseholdRepository households = mock(HouseholdRepository.class);
	private final AssistanceRepository assistance = mock(AssistanceRepository.class);
	private final AssistanceHistoryTool tool = new AssistanceHistoryTool(households, assistance, CLOCK);

	private final Household tran = new Household(UUID.randomUUID(), "HH-0001", "Tran", "Blacktown", "2148", null,
			true, Instant.EPOCH);

	@Test
	void returnsDateTypeAndAmountWithinASydneyDateWindow() {
		given(households.findByRef("HH-0001")).willReturn(Optional.of(tran));
		given(assistance.findSince(tran.getId(), LocalDate.of(2026, 9, 6))).willReturn(List.of(
				new Assistance(UUID.randomUUID(), "AS-00001", tran, LocalDate.of(2026, 9, 15),
						NeedCategory.ENERGY_BILL, new BigDecimal("280.00"), "Electricity disconnection notice")));

		var history = tool.getAssistanceHistory("HH-0001", 30);

		assertThat(history.householdRef()).isEqualTo("HH-0001");
		assertThat(history.from()).isEqualTo(LocalDate.of(2026, 9, 6));
		assertThat(history.to()).isEqualTo(LocalDate.of(2026, 10, 6));
		assertThat(history.assistance()).containsExactly(new AssistanceHistoryTool.AssistanceEntry(
				LocalDate.of(2026, 9, 15), NeedCategory.ENERGY_BILL, new BigDecimal("280.00")));
	}

	@Test
	void acceptsARefInAnyCase() {
		given(households.findByRef("HH-0001")).willReturn(Optional.of(tran));
		given(assistance.findSince(any(), any())).willReturn(List.of());

		assertThat(tool.getAssistanceHistory(" hh-0001 ", 90).householdRef()).isEqualTo("HH-0001");
		verify(households).findByRef("HH-0001");
	}

	@Test
	void anUnknownHouseholdIsAnErrorNotAnEmptyHistory() {
		given(households.findByRef("HH-9999")).willReturn(Optional.empty());

		assertThatThrownBy(() -> tool.getAssistanceHistory("HH-9999", 90))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("HH-9999");
	}

	@Test
	void rejectsAWindowOutsideOneToTenYears() {
		assertThatThrownBy(() -> tool.getAssistanceHistory("HH-0001", 0)).hasMessageContaining("sinceDays");
		assertThatThrownBy(() -> tool.getAssistanceHistory("HH-0001", 3651)).hasMessageContaining("sinceDays");
		assertThatThrownBy(() -> tool.getAssistanceHistory("HH-0001", null)).hasMessageContaining("sinceDays");
	}
}
