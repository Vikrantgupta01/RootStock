package com.sinewlabs.vinnies.mcp.localservice;

import com.sinewlabs.vinnies.mcp.text.Text;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Picks referral candidates for a need in a suburb. Pure: no database, so the
 * ordering is tested directly.
 *
 * <p>Services in the suburb come first (a small misspelling still counts). When
 * the suburb has none, the other services for the same need are offered instead,
 * flagged {@code inSuburb = false}: a referral is still possible, and nothing is
 * passed off as local. There is no map data, so no claim of "nearby" is made.
 */
public final class LocalServiceSearch {

	static final int MAX_RESULTS = 5;

	/** A referral candidate: what a volunteer needs to send someone there, nothing more. */
	public record ServiceMatch(String serviceRef, String name, NeedCategory needType, String address, String suburb,
			String phone, String hours, String eligibility, boolean inSuburb) {
	}

	private LocalServiceSearch() {
	}

	/**
	 * @param services every service for {@code needType}
	 */
	public static List<ServiceMatch> search(List<LocalService> services, NeedCategory needType, String suburb) {
		List<LocalService> forNeed = services.stream().filter(s -> s.getNeedCategory() == needType).toList();
		List<LocalService> inSuburb = forNeed.stream().filter(s -> Text.sameSuburb(suburb, s.getSuburb())).toList();

		Stream<ServiceMatch> matches = inSuburb.isEmpty()
				? forNeed.stream().sorted(BY_SUBURB_THEN_NAME).map(s -> match(s, false))
				: inSuburb.stream().sorted(BY_SUBURB_THEN_NAME).map(s -> match(s, true));
		return matches.limit(MAX_RESULTS).toList();
	}

	private static final Comparator<LocalService> BY_SUBURB_THEN_NAME =
			Comparator.comparing(LocalService::getSuburb).thenComparing(LocalService::getName);

	private static ServiceMatch match(LocalService s, boolean inSuburb) {
		String address = s.getAddress() + ", " + s.getSuburb() + " NSW " + s.getPostcode();
		return new ServiceMatch(s.getRef(), s.getName(), s.getNeedCategory(), address, s.getSuburb(), s.getPhone(),
				s.getHours(), s.getEligibility(), inSuburb);
	}
}
