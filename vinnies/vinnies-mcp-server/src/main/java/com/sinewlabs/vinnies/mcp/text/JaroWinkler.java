package com.sinewlabs.vinnies.mcp.text;

/**
 * Jaro-Winkler string similarity, 0 (nothing in common) to 1 (identical). Suited
 * to short names: it tolerates transposed and substituted letters (Smith/Smyth,
 * Catherine/Katherine) and rewards a shared beginning.
 */
public final class JaroWinkler {

	private static final double PREFIX_SCALE = 0.1;
	private static final int MAX_PREFIX = 4;

	private JaroWinkler() {
	}

	public static double similarity(String a, String b) {
		if (a.equals(b)) {
			return 1.0;
		}
		if (a.isEmpty() || b.isEmpty()) {
			return 0.0;
		}
		double jaro = jaro(a, b);
		int prefix = 0;
		while (prefix < Math.min(MAX_PREFIX, Math.min(a.length(), b.length()))
				&& a.charAt(prefix) == b.charAt(prefix)) {
			prefix++;
		}
		return jaro + prefix * PREFIX_SCALE * (1 - jaro);
	}

	private static double jaro(String a, String b) {
		int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
		boolean[] aMatched = new boolean[a.length()];
		boolean[] bMatched = new boolean[b.length()];
		int matches = 0;
		for (int i = 0; i < a.length(); i++) {
			int from = Math.max(0, i - window);
			int to = Math.min(b.length() - 1, i + window);
			for (int j = from; j <= to; j++) {
				if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
					aMatched[i] = true;
					bMatched[j] = true;
					matches++;
					break;
				}
			}
		}
		if (matches == 0) {
			return 0.0;
		}
		int transpositions = 0;
		int j = 0;
		for (int i = 0; i < a.length(); i++) {
			if (!aMatched[i]) {
				continue;
			}
			while (!bMatched[j]) {
				j++;
			}
			if (a.charAt(i) != b.charAt(j)) {
				transpositions++;
			}
			j++;
		}
		double m = matches;
		return (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
	}
}
