package com.sinewlabs.vinnies.mcp.demodata;

import com.sinewlabs.vinnies.mcp.demodata.DemoData.AssistanceRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.HouseholdRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.PersonRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.ServiceRow;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the fictional data set into the app's schema. Plain JDBC batches rather
 * than JPA: ids are assigned up front, and JPA would look each one up before
 * inserting it, a few hundred round trips to RDS for nothing.
 *
 * <p>Both operations run in one transaction, so a failure leaves the tables as
 * they were.
 */
@Component
public class DemoDataLoader {

	/** Child tables first, so a delete never trips a foreign key. */
	private static final List<String> TABLES = List.of("assistance", "person", "household", "local_service");

	public enum Outcome {
		SEEDED, ALREADY_SEEDED, RESET
	}

	public record Summary(Outcome outcome, long households, long people, long assistance, long services,
			String fingerprint) {
	}

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public DemoDataLoader(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Loads the demo data into empty tables. Does nothing when it is already
	 * there; refuses when the tables hold anything else, rather than mixing the
	 * two. Running it twice leaves the same state.
	 */
	@Transactional
	public Summary seed() {
		if (isEmpty()) {
			insert(generate());
			return summary(Outcome.SEEDED);
		}
		if (looksSeeded()) {
			return summary(Outcome.ALREADY_SEEDED);
		}
		throw new IllegalStateException("The tables already hold data that is not the demo data set. "
				+ "Run 'reset' to replace it with the demo data.");
	}

	/** Wipes the app's four tables and loads the demo data again: back to a clean demo. */
	@Transactional
	public Summary reset() {
		jdbc.execute("TRUNCATE " + String.join(", ", TABLES));
		insert(generate());
		return summary(Outcome.RESET);
	}

	DemoData generate() {
		return DemoDataGenerator.generate(LocalDate.now(clock.withZone(DemoDataGenerator.SYDNEY)));
	}

	private boolean isEmpty() {
		return TABLES.stream().allMatch(t -> count(t) == 0);
	}

	/** The right number of rows, and the first demo household where it belongs. */
	private boolean looksSeeded() {
		return count("household") == DemoDataGenerator.HOUSEHOLDS
				&& count("assistance") == DemoDataGenerator.ASSISTANCE
				&& count("local_service") == DemoDataGenerator.SERVICES
				&& jdbc.queryForObject("SELECT count(*) FROM household WHERE ref = 'HH-0001' AND family_name = 'Tran'",
						Long.class) == 1;
	}

	private void insert(DemoData data) {
		List<Object[]> households = new ArrayList<>();
		List<Object[]> people = new ArrayList<>();
		for (HouseholdRow h : data.households()) {
			households.add(new Object[] { h.id(), h.ref(), h.familyName(), h.suburb(), h.postcode(), h.phone(),
					h.consentGiven(), h.createdAt().atOffset(ZoneOffset.UTC) });
			for (PersonRow p : h.members()) {
				people.add(new Object[] { p.id(), h.id(), p.givenName(), p.familyName(), p.relationship().name(),
						p.birthYear() });
			}
		}
		jdbc.batchUpdate("INSERT INTO household (id, ref, family_name, suburb, postcode, phone, consent_given, "
				+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", households);
		jdbc.batchUpdate("INSERT INTO person (id, household_id, given_name, family_name, relationship, birth_year) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", people);

		List<Object[]> assistance = new ArrayList<>();
		for (AssistanceRow a : data.assistance()) {
			assistance.add(new Object[] { a.id(), a.ref(), a.householdId(), Date.valueOf(a.assistedOn()),
					a.category().name(), a.amountAud(), a.description() });
		}
		jdbc.batchUpdate("INSERT INTO assistance (id, ref, household_id, assisted_on, category, amount_aud, "
				+ "description) VALUES (?, ?, ?, ?, ?, ?, ?)", assistance);

		List<Object[]> services = new ArrayList<>();
		for (ServiceRow s : data.services()) {
			services.add(new Object[] { s.id(), s.ref(), s.name(), s.needCategory().name(), s.suburb(), s.postcode(),
					s.address(), s.phone(), s.hours(), s.eligibility() });
		}
		jdbc.batchUpdate("INSERT INTO local_service (id, ref, name, need_category, suburb, postcode, address, phone, "
				+ "hours, eligibility) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", services);
	}

	private Summary summary(Outcome outcome) {
		return new Summary(outcome, count("household"), count("person"), count("assistance"), count("local_service"),
				fingerprint());
	}

	/**
	 * An md5 over every row of every table, in a fixed order. Equal fingerprints
	 * mean equal data, which is how "running it twice gives the same state" is
	 * checked rather than assumed.
	 */
	public String fingerprint() {
		StringBuilder all = new StringBuilder();
		for (String table : TABLES) {
			all.append(jdbc.queryForObject(
					"SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '-') FROM " + table + " t",
					String.class));
		}
		return jdbc.queryForObject("SELECT md5(?)", String.class, all.toString());
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}
}
