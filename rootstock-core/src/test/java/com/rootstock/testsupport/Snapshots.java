package com.rootstock.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares generated text with a file committed next to the tests. When the
 * output changes on purpose, rewrite the files with
 * {@code mvn test -Dsnapshot.update=true} and review the diff in git.
 */
public final class Snapshots {

	private Snapshots() {
	}

	public static boolean updating() {
		return Boolean.getBoolean("snapshot.update");
	}

	public static void assertMatches(Path file, String actual) {
		try {
			if (updating()) {
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.writeString(file, actual, StandardCharsets.UTF_8);
				return;
			}
			if (!Files.exists(file)) {
				fail("No snapshot at " + file + "; create it with mvn test -Dsnapshot.update=true and review it");
			}
			assertThat(actual)
					.as("%s is out of date; if the change is intended, run mvn test -Dsnapshot.update=true", file)
					.isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
