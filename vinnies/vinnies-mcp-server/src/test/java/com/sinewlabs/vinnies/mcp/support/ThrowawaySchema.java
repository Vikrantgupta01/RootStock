package com.sinewlabs.vinnies.mcp.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * A schema on RDS that exists only for one test run: created and built from the
 * project's own db/setup.sql, then dropped. The app under test is pointed at it,
 * so tests never touch vinnies_mock or anything else in the database.
 *
 * <p>The name carries the start time (vinnies_test_yyyyMMddHHmm_xxxxxx), so a
 * schema left behind by a killed run is easy to spot and drop by hand.
 */
public final class ThrowawaySchema implements AutoCloseable {

	static final String PREFIX = "vinnies_test_";
	private static final String SETUP_SCRIPT = "db/setup.sql";
	private static final String SCRIPT_SCHEMA = "vinnies_mock";

	private final String name;
	private final String url;
	private final String username;
	private final String password;

	private ThrowawaySchema(String name, String url, String username, String password) {
		this.name = name;
		this.url = url;
		this.username = username;
		this.password = password;
	}

	/** Creates the schema and its tables, using the same connection settings as the app. */
	public static ThrowawaySchema create(Environment env) {
		String url = require(env, "VINNIES_DB_URL");
		String username = require(env, "VINNIES_DB_USERNAME");
		String password = require(env, "VINNIES_DB_PASSWORD");
		String name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + "_"
				+ UUID.randomUUID().toString().substring(0, 6);

		ThrowawaySchema schema = new ThrowawaySchema(name, url, username, password);
		schema.build();
		return schema;
	}

	public String name() {
		return name;
	}

	/** Runs db/setup.sql with this schema's name in place of vinnies_mock. */
	private void build() {
		String script;
		try {
			script = Files.readString(Path.of(SETUP_SCRIPT), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + SETUP_SCRIPT + " (tests run from the project folder)", ex);
		}
		if (!script.contains(SCRIPT_SCHEMA)) {
			throw new IllegalStateException(SETUP_SCRIPT + " no longer names schema " + SCRIPT_SCHEMA);
		}
		try (Connection connection = connect()) {
			ScriptUtils.executeSqlScript(connection,
					new ByteArrayResource(script.replace(SCRIPT_SCHEMA, name).getBytes(StandardCharsets.UTF_8)));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not create test schema " + name, ex);
		}
	}

	/** Drops the schema and everything in it. Refuses any name it did not make. */
	@Override
	public void close() {
		if (!name.startsWith(PREFIX)) {
			throw new IllegalStateException("Refusing to drop " + name + ": not a test schema");
		}
		try (Connection connection = connect()) {
			connection.createStatement().execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not drop test schema " + name + "; drop it by hand", ex);
		}
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	private static String require(Environment env, String key) {
		String value = env.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(key + " is not set. Integration tests run against RDS: "
					+ "load the settings first (set -a && source .env && set +a).");
		}
		return value;
	}
}
