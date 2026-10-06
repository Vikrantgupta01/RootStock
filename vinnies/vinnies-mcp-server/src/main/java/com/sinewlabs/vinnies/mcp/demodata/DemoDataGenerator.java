package com.sinewlabs.vinnies.mcp.demodata;

import static com.sinewlabs.vinnies.mcp.household.Relationship.CHILD;
import static com.sinewlabs.vinnies.mcp.household.Relationship.PARTNER;
import static com.sinewlabs.vinnies.mcp.household.Relationship.PRIMARY_CONTACT;

import com.sinewlabs.vinnies.mcp.demodata.DemoData.AssistanceRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.HouseholdRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.PersonRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.ServiceRow;
import com.sinewlabs.vinnies.mcp.household.Relationship;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Generates the fictional Vinnies data set: about 50 households, 200 assistance
 * records and 40 local services across Sydney suburbs.
 *
 * <p>Repeatable by construction: one fixed random seed, ids derived from each
 * row's ref (name-based UUIDs), and every date measured back from {@code today}.
 * The same day therefore always gives identical rows. Dates move with the day
 * the data is generated, so history stays recent whenever the demo is reset.
 *
 * <p>Everything is invented except suburb names and postcodes. Phone numbers come
 * only from ranges ACMA reserves for fiction (02 5550 xxxx, 02 7010 xxxx), so no
 * number here can ring a real person or service.
 */
public final class DemoDataGenerator {

	public static final long SEED = 20_261_006L;
	public static final int HOUSEHOLDS = 50;
	public static final int ASSISTANCE = 200;
	public static final int SERVICES = 40;

	static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

	record Suburb(String name, String postcode) {
	}

	static final Suburb BLACKTOWN = new Suburb("Blacktown", "2148");
	static final Suburb MOUNT_DRUITT = new Suburb("Mount Druitt", "2770");
	static final Suburb PARRAMATTA = new Suburb("Parramatta", "2150");
	static final Suburb LIVERPOOL = new Suburb("Liverpool", "2170");

	static final List<Suburb> SUBURBS = List.of(
			BLACKTOWN, MOUNT_DRUITT, PARRAMATTA, LIVERPOOL,
			new Suburb("Campbelltown", "2560"),
			new Suburb("Penrith", "2750"),
			new Suburb("Bankstown", "2200"),
			new Suburb("Fairfield", "2165"),
			new Suburb("Auburn", "2144"),
			new Suburb("Marrickville", "2204"),
			new Suburb("Redfern", "2016"),
			new Suburb("Hurstville", "2220"));

	private static final List<String> FAMILY_NAMES = List.of(
			"Nguyen", "Tran", "Smith", "Brown", "Wilson", "Taylor", "Singh", "Kaur", "Patel", "Ali",
			"Khan", "Chen", "Wang", "Li", "Lee", "Kim", "Martin", "Jones", "Williams", "Thompson",
			"Kelly", "Murphy", "O'Brien", "Papadopoulos", "Rossi", "Haddad", "Nasser", "Fernando", "Silva",
			"Lopez", "Garcia", "Walker", "Hall", "Young", "King", "Wright", "Scott", "Mohammed", "Ibrahim");

	private static final List<String> ADULT_NAMES = List.of(
			"Mai", "Linh", "Anh", "Sarah", "Jessica", "Emily", "Olivia", "Fatima", "Aisha", "Priya",
			"Harpreet", "Mei", "Jing", "Grace", "Hannah", "Sophie", "Leila", "Maria", "Rosa", "Helen",
			"James", "Michael", "David", "Daniel", "Joshua", "Ahmed", "Omar", "Raj", "Arjun", "Wei",
			"Jun", "Tom", "Peter", "Paul", "Hassan", "Carlos", "Nikos", "Sean", "Mark", "Tuan");

	private static final List<String> CHILD_NAMES = List.of(
			"Liam", "Noah", "Lucas", "Ethan", "Jack", "Mia", "Ava", "Isla", "Zara", "Amelia",
			"Ruby", "Leo", "Kai", "Yusuf", "Aria", "Noor", "Ivy", "Max", "Ella", "Sami");

	private static final Map<NeedCategory, List<String>> DESCRIPTIONS = Map.of(
			NeedCategory.FOOD, List.of("Supermarket vouchers", "Food hamper", "Food parcel and vouchers"),
			NeedCategory.ENERGY_BILL, List.of("Overdue electricity bill", "Gas bill arrears",
					"Electricity disconnection notice"),
			NeedCategory.RENT, List.of("Rent arrears", "Bond contribution", "Rent shortfall after job loss"));

	private static final Map<NeedCategory, List<String>> SERVICE_NAMES = Map.of(
			NeedCategory.FOOD, List.of("%s Community Pantry", "%s Food Relief Hub"),
			NeedCategory.ENERGY_BILL, List.of("%s Energy Bill Help Desk", "%s Utility Hardship Service"),
			NeedCategory.RENT, List.of("%s Tenancy Support Service", "%s Housing Help Centre"));

	private static final Map<NeedCategory, List<String>> ELIGIBILITY = Map.of(
			NeedCategory.FOOD, List.of(
					"Anyone in need; bring ID if you have it",
					"Health Care Card or Centrelink income statement",
					"Referral from a Vinnies member or caseworker"),
			NeedCategory.ENERGY_BILL, List.of(
					"Must bring a current bill or disconnection notice",
					"Households on Centrelink payments with energy debt",
					"Customers of any NSW energy retailer facing hardship"),
			NeedCategory.RENT, List.of(
					"Renters at risk of eviction; bring the tenancy agreement",
					"Private renters with arrears under 8 weeks",
					"Social housing tenants and private renters on low incomes"));

	private static final List<String> HOURS = List.of(
			"Mon-Fri 9:00-15:00", "Tue and Thu 10:00-14:00", "Mon-Wed 9:30-16:30",
			"Wed-Sat 10:00-13:00", "Mon-Fri 8:30-17:00 (appointment preferred)");

	private static final List<String> STREETS = List.of(
			"Church", "Station", "George", "High", "Main", "Railway", "Victoria", "Albert", "King", "Queen");

	private DemoDataGenerator() {
	}

	public static DemoData generate(LocalDate today) {
		Random random = new Random(SEED);
		List<HouseholdRow> households = new ArrayList<>(demoHouseholds(today));
		for (int n = households.size() + 1; n <= HOUSEHOLDS; n++) {
			households.add(randomHousehold(n, today, random));
		}
		List<AssistanceRow> assistance = assistance(households, today, random);
		List<ServiceRow> services = services(random);
		return new DemoData(List.copyOf(households), List.copyOf(assistance), List.copyOf(services));
	}

	// ---- households ----------------------------------------------------------

	/**
	 * Hand-made households first, so the find_household demo has known cases:
	 * two Tran households in neighbouring suburbs, and a Smith and a Smyth in the
	 * same suburb with near-identical first names.
	 */
	private static List<HouseholdRow> demoHouseholds(LocalDate today) {
		return List.of(
				household(1, "Tran", BLACKTOWN, true, today.minusDays(400), List.of(
						new Member("Linh", "Tran", PRIMARY_CONTACT, 1984),
						new Member("Minh", "Tran", PARTNER, 1981),
						new Member("An", "Tran", CHILD, 2014),
						new Member("Bao", "Tran", CHILD, 2017))),
				household(2, "Tran", MOUNT_DRUITT, true, today.minusDays(250), List.of(
						new Member("Thanh", "Tran", PRIMARY_CONTACT, 1979),
						new Member("Hoa", "Tran", PARTNER, 1983))),
				household(3, "Smith", PARRAMATTA, true, today.minusDays(600), List.of(
						new Member("Catherine", "Smith", PRIMARY_CONTACT, 1990),
						new Member("Lily", "Smith", CHILD, 2019))),
				noPhone(household(4, "Smyth", PARRAMATTA, false, today.minusDays(30), List.of(
						new Member("Katherine", "Smyth", PRIMARY_CONTACT, 1988)))),
				household(5, "Haddad", LIVERPOOL, true, today.minusDays(800), List.of(
						new Member("Fatima", "Haddad", PRIMARY_CONTACT, 1975),
						new Member("Omar", "Haddad", PARTNER, 1972),
						new Member("Sami", "Haddad", CHILD, 2010),
						new Member("Leila", "Haddad", CHILD, 2013))));
	}

	private static HouseholdRow randomHousehold(int n, LocalDate today, Random random) {
		String familyName = pick(FAMILY_NAMES, random);
		Suburb suburb = pick(SUBURBS, random);
		boolean consent = random.nextInt(100) < 80;
		LocalDate created = today.minusDays(30 + random.nextInt(900));

		List<Member> members = new ArrayList<>();
		members.add(new Member(pick(ADULT_NAMES, random), familyName, PRIMARY_CONTACT, 1950 + random.nextInt(51)));
		if (random.nextBoolean()) {
			String partnerFamily = random.nextInt(100) < 85 ? familyName : pick(FAMILY_NAMES, random);
			members.add(new Member(pick(ADULT_NAMES, random), partnerFamily, PARTNER, 1950 + random.nextInt(51)));
		}
		int children = random.nextInt(5);
		for (int c = 0; c < children; c++) {
			members.add(new Member(pick(CHILD_NAMES, random), familyName, CHILD,
					today.getYear() - 1 - random.nextInt(17)));
		}

		HouseholdRow row = household(n, familyName, suburb, consent, created, members);
		return random.nextInt(100) < 85 ? row : noPhone(row);
	}

	private record Member(String givenName, String familyName, Relationship relationship, int birthYear) {
	}

	private static HouseholdRow household(int n, String familyName, Suburb suburb, boolean consent,
			LocalDate created, List<Member> members) {
		String ref = "HH-%04d".formatted(n);
		List<PersonRow> people = new ArrayList<>();
		for (int i = 0; i < members.size(); i++) {
			Member m = members.get(i);
			people.add(new PersonRow(id("person", ref + "-" + (i + 1)), m.givenName(), m.familyName(),
					m.relationship(), m.birthYear()));
		}
		return new HouseholdRow(id("household", ref), ref, familyName, suburb.name(), suburb.postcode(),
				"02 5550 %04d".formatted(100 + n), consent, atTenAm(created), List.copyOf(people));
	}

	private static HouseholdRow noPhone(HouseholdRow h) {
		return new HouseholdRow(h.id(), h.ref(), h.familyName(), h.suburb(), h.postcode(), null, h.consentGiven(),
				h.createdAt(), h.members());
	}

	// ---- assistance ----------------------------------------------------------

	private static List<AssistanceRow> assistance(List<HouseholdRow> households, LocalDate today, Random random) {
		List<AssistanceRow> rows = new ArrayList<>();
		// Recent, repeated help for the first demo household: material for the
		// frequency rule later (same type twice inside a few months).
		UUID tran = households.get(0).id();
		rows.add(assistance(rows.size() + 1, tran, today.minusDays(21), NeedCategory.ENERGY_BILL, 280,
				"Electricity disconnection notice"));
		rows.add(assistance(rows.size() + 1, tran, today.minusDays(21), NeedCategory.FOOD, 120,
				"Supermarket vouchers"));
		rows.add(assistance(rows.size() + 1, tran, today.minusDays(75), NeedCategory.ENERGY_BILL, 250,
				"Overdue electricity bill"));

		// Only households known for a while get random history, and never from
		// before they were registered. Newer ones (HH-0004, 30 days old) stay
		// without history: the "new household" case.
		List<HouseholdRow> established = households.stream()
				.filter(h -> ageInDays(h, today) > 90)
				.toList();
		NeedCategory[] categories = NeedCategory.values();
		while (rows.size() < ASSISTANCE) {
			HouseholdRow household = pick(established, random);
			int window = (int) Math.min(365, ageInDays(household, today));
			NeedCategory category = categories[random.nextInt(categories.length)];
			rows.add(assistance(rows.size() + 1, household.id(), today.minusDays(1 + random.nextInt(window - 1)),
					category, amount(category, random), pick(DESCRIPTIONS.get(category), random)));
		}
		return rows;
	}

	private static AssistanceRow assistance(int n, UUID householdId, LocalDate on, NeedCategory category, int amount,
			String description) {
		String ref = "AS-%05d".formatted(n);
		return new AssistanceRow(id("assistance", ref), ref, householdId, on, category,
				BigDecimal.valueOf(amount).setScale(2), description);
	}

	/** Whole tens, in a range typical for each kind of help. */
	private static int amount(NeedCategory category, Random random) {
		return switch (category) {
			case FOOD -> 50 + 10 * random.nextInt(11);          // $50-$150
			case ENERGY_BILL -> 100 + 10 * random.nextInt(31);  // $100-$400
			case RENT -> 200 + 10 * random.nextInt(41);         // $200-$600
		};
	}

	// ---- services ------------------------------------------------------------

	/** 16 food, 12 energy and 12 rent services, spread so every suburb has some. */
	private static List<ServiceRow> services(Random random) {
		Map<NeedCategory, Integer> perCategory = Map.of(
				NeedCategory.FOOD, 16, NeedCategory.ENERGY_BILL, 12, NeedCategory.RENT, 12);
		List<ServiceRow> rows = new ArrayList<>();
		for (NeedCategory category : NeedCategory.values()) {
			for (int k = 0; k < perCategory.get(category); k++) {
				Suburb suburb = SUBURBS.get(k % SUBURBS.size());
				List<String> names = SERVICE_NAMES.get(category);
				String name = names.get((k / SUBURBS.size()) % names.size()).formatted(suburb.name());
				String ref = "SVC-%03d".formatted(rows.size() + 1);
				rows.add(new ServiceRow(id("service", ref), ref, name, category, suburb.name(), suburb.postcode(),
						(1 + random.nextInt(250)) + " " + pick(STREETS, random) + " Street",
						"02 7010 %04d".formatted(100 + rows.size() + 1),
						pick(HOURS, random), pick(ELIGIBILITY.get(category), random)));
			}
		}
		return rows;
	}

	// ---- helpers -------------------------------------------------------------

	private static long ageInDays(HouseholdRow household, LocalDate today) {
		LocalDate created = household.createdAt().atZone(SYDNEY).toLocalDate();
		return java.time.temporal.ChronoUnit.DAYS.between(created, today);
	}

	/** Name-based UUID: the same ref always gives the same id, whatever order rows are made in. */
	static UUID id(String kind, String ref) {
		return UUID.nameUUIDFromBytes(("vinnies:" + kind + ":" + ref).getBytes(StandardCharsets.UTF_8));
	}

	private static Instant atTenAm(LocalDate date) {
		return date.atTime(LocalTime.of(10, 0)).atZone(SYDNEY).toInstant();
	}

	private static <T> T pick(List<T> values, Random random) {
		return values.get(random.nextInt(values.size()));
	}
}
