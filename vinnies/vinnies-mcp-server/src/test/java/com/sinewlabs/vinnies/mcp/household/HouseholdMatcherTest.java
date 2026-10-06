package com.sinewlabs.vinnies.mcp.household;

import static com.sinewlabs.vinnies.mcp.household.HouseholdMatcher.MatchedOn.NAME;
import static com.sinewlabs.vinnies.mcp.household.HouseholdMatcher.MatchedOn.PHONE;
import static com.sinewlabs.vinnies.mcp.household.HouseholdMatcher.MatchedOn.SUBURB;
import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.household.HouseholdMatcher.HouseholdMatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The find_household scenarios the demo relies on, on households shaped like the seed data. */
class HouseholdMatcherTest {

	private final List<Household> households = List.of(
			household("HH-0001", "Tran", "Blacktown", "02 5550 0101",
					new String[] { "Linh", "Tran" }, new String[] { "Minh", "Tran" }, new String[] { "An", "Tran" }),
			household("HH-0002", "Tran", "Mount Druitt", "02 5550 0102",
					new String[] { "Thanh", "Tran" }, new String[] { "Hoa", "Tran" }),
			household("HH-0003", "Smith", "Parramatta", "02 5550 0103",
					new String[] { "Catherine", "Smith" }, new String[] { "Lily", "Smith" }),
			household("HH-0004", "Smyth", "Parramatta", null,
					new String[] { "Katherine", "Smyth" }),
			household("HH-0005", "Haddad", "Liverpool", "02 5550 0105",
					new String[] { "Fatima", "Haddad" }, new String[] { "Omar", "Haddad" }),
			// Lookalikes seen in the seed data: share one word, or a near-spelling, with the Trans.
			household("HH-0010", "Kaur", "Auburn", null,
					new String[] { "Linh", "Kaur" }, new String[] { "Tuan", "Kelly" }),
			household("HH-0006", "Smith", "Auburn", null,
					new String[] { "Nikos", "Smith" }, new String[] { "Ethan", "Smith" }));

	@Test
	void fullNameAndSuburbRankTheRightHouseholdFirstWithTheTopScoreShortOfAPhone() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "Linh Tran", "Blacktown", null);

		assertThat(matches.get(0).householdRef()).isEqualTo("HH-0001");
		assertThat(matches.get(0).matchScore()).isEqualTo(0.95);
		assertThat(matches.get(0).matchedOn()).containsExactly(NAME, SUBURB);
		assertThat(matches.get(0).matchedName()).isEqualTo("Linh Tran");
	}

	@Test
	void suburbSeparatesHouseholdsWithTheSameName() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "Tran", "Mount Druitt", null);

		assertThat(matches).extracting(HouseholdMatch::householdRef).containsExactly("HH-0002", "HH-0001");
		assertThat(matches.get(0).matchScore()).isGreaterThan(matches.get(1).matchScore());
	}

	@Test
	void misspeltNamesStillMatchAndNearTwinsBothComeBack() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "Katherine Smith", "Paramatta", null);

		assertThat(matches).extracting(HouseholdMatch::householdRef).containsExactlyInAnyOrder("HH-0003", "HH-0004");
		assertThat(matches).allSatisfy(m -> {
			assertThat(m.matchScore()).isBetween(0.85, 0.99);
			assertThat(m.matchedOn()).contains(SUBURB);   // "Paramatta" typo still counts
		});
	}

	@Test
	void anExactPhoneWinsEvenOverABetterSpelledName() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "Katherine Smyth", "Parramatta",
				"+61 2 5550 0103");

		assertThat(matches.get(0).householdRef()).isEqualTo("HH-0003");
		assertThat(matches.get(0).matchScore()).isEqualTo(1.0);
		assertThat(matches.get(0).matchedOn()).contains(PHONE);
		// Strictly ahead of the perfectly spelled HH-0004, not tied with it.
		assertThat(matches.get(1).householdRef()).isEqualTo("HH-0004");
		assertThat(matches.get(1).matchScore()).isLessThan(1.0);
	}

	@Test
	void lookalikeWordsDoNotMatch() {
		assertThat(HouseholdMatcher.rank(households, "Tran", "Mount Druitt", null))
				.extracting(HouseholdMatch::householdRef)
				.containsExactly("HH-0002", "HH-0001");   // not Tuan Kelly, not Ethan Smith
		assertThat(HouseholdMatcher.rank(households, "Linh Tran", "Blacktown", null))
				.extracting(HouseholdMatch::householdRef)
				.doesNotContain("HH-0010");               // a shared first name alone is not enough
	}

	@Test
	void anInitialCountsTowardsAFirstName() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "C Smith", "Parramatta", null);

		assertThat(matches.get(0).householdRef()).isEqualTo("HH-0003");
	}

	@Test
	void aSharedSuburbAloneIsNotAMatch() {
		assertThat(HouseholdMatcher.rank(households, "Zebedee Quinn", "Parramatta", null)).isEmpty();
	}

	@Test
	void aWrongPhoneDoesNotHideAGoodNameMatch() {
		List<HouseholdMatch> matches = HouseholdMatcher.rank(households, "Fatima Haddad", "Liverpool",
				"02 5550 9999");

		assertThat(matches.get(0).householdRef()).isEqualTo("HH-0005");
		assertThat(matches.get(0).matchedOn()).doesNotContain(PHONE);
	}

	@Test
	void returnsAtMostFiveCandidates() {
		List<Household> many = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			many.add(household("HH-01%02d".formatted(i), "Tran", "Blacktown", null, new String[] { "Linh", "Tran" }));
		}
		assertThat(HouseholdMatcher.rank(many, "Linh Tran", "Blacktown", null)).hasSize(5);
	}

	@Test
	void normalisesAccentsPunctuationAndPhoneFormats() {
		assertThat(HouseholdMatcher.normalise("  O'Brien-Nguyễn ")).isEqualTo("obriennguyen");
		assertThat(HouseholdMatcher.digits("+61 2 5550 0101")).isEqualTo("0255500101");
		assertThat(HouseholdMatcher.digits("(02) 5550-0101")).isEqualTo("0255500101");
	}

	private static Household household(String ref, String familyName, String suburb, String phone,
			String[]... members) {
		Household household = new Household(UUID.randomUUID(), ref, familyName, suburb, "2000", phone, true,
				Instant.EPOCH);
		for (int i = 0; i < members.length; i++) {
			household.addMember(UUID.randomUUID(), members[i][0], members[i][1],
					i == 0 ? Relationship.PRIMARY_CONTACT : Relationship.OTHER_ADULT, null);
		}
		return household;
	}
}
