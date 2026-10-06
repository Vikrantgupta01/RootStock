package com.sinewlabs.vinnies.mcp.household;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranks households against a name, a suburb and optionally a phone number.
 * Pure: no database, so the scoring is tested directly.
 *
 * <p>Score, 0 to 1:
 * <ul>
 * <li>An exact phone match (after normalising spaces and +61) is 1.0: a number is
 * far stronger evidence than a spelling.</li>
 * <li>Otherwise {@code 0.75 x name similarity + 0.2 if the suburb matches}, so at
 * most 0.95: without a phone nothing is certain, and a phone match always ranks
 * first. Name similarity is the best Jaro-Winkler match of the query, word by
 * word, against each member's full name and the family name, so "Tran", "Linh
 * Tran" and "Lin Tran" all find Linh Tran. A word only counts if it is at least
 * {@value #MIN_WORD_SIMILARITY} similar: Smith/Smyth and Catherine/Katherine pass,
 * Tran/Tuan does not. A wrong phone is ignored: people change numbers.</li>
 * </ul>
 * A household needs a name similarity of at least {@value #MIN_NAME_SIMILARITY} (or the
 * phone) to be a candidate at all, so a shared suburb alone never makes a match.
 */
public final class HouseholdMatcher {

	static final double MIN_NAME_SIMILARITY = 0.75;
	static final double MIN_WORD_SIMILARITY = 0.87;
	static final double MIN_SCORE = 0.6;
	static final int MAX_RESULTS = 5;
	private static final double NAME_WEIGHT = 0.75;
	private static final double SUBURB_BOOST = 0.2;
	private static final double SUBURB_SIMILARITY = 0.9;

	public enum MatchedOn {
		PHONE, NAME, SUBURB
	}

	/** One candidate, with only what a caller needs to choose; no contact details. */
	public record HouseholdMatch(String householdRef, String primaryContact, String suburb, double matchScore,
			List<MatchedOn> matchedOn, String matchedName) {
	}

	private HouseholdMatcher() {
	}

	public static List<HouseholdMatch> rank(List<Household> households, String name, String suburb, String phone) {
		List<String> nameQuery = words(name);
		String suburbQuery = normalise(suburb);
		String phoneQuery = digits(phone);

		List<HouseholdMatch> matches = new ArrayList<>();
		for (Household household : households) {
			HouseholdMatch match = score(household, nameQuery, suburbQuery, phoneQuery);
			if (match != null && match.matchScore() >= MIN_SCORE) {
				matches.add(match);
			}
		}
		matches.sort(Comparator.comparingDouble(HouseholdMatch::matchScore).reversed()
				.thenComparing(HouseholdMatch::householdRef));
		return List.copyOf(matches.subList(0, Math.min(MAX_RESULTS, matches.size())));
	}

	private static HouseholdMatch score(Household household, List<String> nameQuery, String suburbQuery,
			String phoneQuery) {
		boolean phoneMatches = !phoneQuery.isEmpty() && phoneQuery.equals(digits(household.getPhone()));

		String bestName = household.getFamilyName();
		double bestSimilarity = nameSimilarity(nameQuery, words(household.getFamilyName()));
		for (Person member : household.getMembers()) {
			double similarity = nameSimilarity(nameQuery, words(member.fullName()));
			if (similarity > bestSimilarity) {
				bestSimilarity = similarity;
				bestName = member.fullName();
			}
		}
		boolean nameMatches = bestSimilarity >= MIN_NAME_SIMILARITY;
		if (!nameMatches && !phoneMatches) {
			return null;
		}
		boolean suburbMatches = JaroWinkler.similarity(suburbQuery, normalise(household.getSuburb()))
				>= SUBURB_SIMILARITY;

		List<MatchedOn> matchedOn = new ArrayList<>();
		if (phoneMatches) {
			matchedOn.add(MatchedOn.PHONE);
		}
		if (nameMatches) {
			matchedOn.add(MatchedOn.NAME);
		}
		if (suburbMatches) {
			matchedOn.add(MatchedOn.SUBURB);
		}
		double score = phoneMatches ? 1.0 : NAME_WEIGHT * bestSimilarity + (suburbMatches ? SUBURB_BOOST : 0.0);
		return new HouseholdMatch(household.getRef(), primaryContact(household), household.getSuburb(),
				round(score), List.copyOf(matchedOn), nameMatches ? bestName : null);
	}

	/**
	 * How well the query's words are covered by the candidate's: for each query
	 * word its best match among the candidate's words, averaged. A one-letter
	 * query word counts as an initial; a word less than MIN_WORD_SIMILARITY alike
	 * counts as no match, so short names don't drift into lookalikes.
	 */
	static double nameSimilarity(List<String> query, List<String> candidate) {
		if (query.isEmpty() || candidate.isEmpty()) {
			return 0.0;
		}
		double total = 0;
		for (String q : query) {
			double best = 0;
			for (String c : candidate) {
				double s = q.length() == 1 ? (c.startsWith(q) ? 0.9 : 0.0) : JaroWinkler.similarity(q, c);
				if (s >= MIN_WORD_SIMILARITY) {
					best = Math.max(best, s);
				}
			}
			total += best;
		}
		return total / query.size();
	}

	private static String primaryContact(Household household) {
		return household.getMembers().stream()
				.filter(p -> p.getRelationship() == Relationship.PRIMARY_CONTACT)
				.map(Person::fullName)
				.findFirst()
				.orElse(household.getFamilyName() + " household");
	}

	/** Lower case, accents and punctuation removed: "O'Brien" and "obrien" compare equal. */
	static String normalise(String text) {
		if (text == null) {
			return "";
		}
		String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").strip();
	}

	static List<String> words(String text) {
		String normalised = normalise(text);
		return normalised.isEmpty() ? List.of() : Arrays.asList(normalised.split(" "));
	}

	/** Digits only, with an Australian +61 prefix turned back into a leading 0. */
	static String digits(String phone) {
		if (phone == null) {
			return "";
		}
		String digits = phone.replaceAll("\\D", "");
		return digits.startsWith("61") && digits.length() == 11 ? "0" + digits.substring(2) : digits;
	}

	private static double round(double score) {
		return Math.round(Math.min(1.0, score) * 100) / 100.0;
	}
}
