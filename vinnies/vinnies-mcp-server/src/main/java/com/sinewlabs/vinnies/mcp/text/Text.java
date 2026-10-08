package com.sinewlabs.vinnies.mcp.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Text helpers shared by the tools that match what a person typed against stored names. */
public final class Text {

	/** Suburbs this alike count as the same: tolerates a typo ("Paramatta"), not a different suburb. */
	public static final double SAME_SUBURB = 0.9;

	private Text() {
	}

	/** Lower case, accents and punctuation removed: "O'Brien" and "obrien" compare equal. */
	public static String normalise(String text) {
		if (text == null) {
			return "";
		}
		String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").strip();
	}

	public static List<String> words(String text) {
		String normalised = normalise(text);
		return normalised.isEmpty() ? List.of() : Arrays.asList(normalised.split(" "));
	}

	/** Whether two suburb names mean the same suburb, allowing for small misspellings. */
	public static boolean sameSuburb(String a, String b) {
		return JaroWinkler.similarity(normalise(a), normalise(b)) >= SAME_SUBURB;
	}
}
