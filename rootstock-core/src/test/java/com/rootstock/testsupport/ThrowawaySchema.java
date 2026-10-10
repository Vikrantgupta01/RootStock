package com.rootstock.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.core.env.Environment;

/**
 * A schema in Rootstock's RDS database that exists for one test run only, so
 * integration tests never touch the application's own tables (CLAUDE.md). Flyway
 * builds it from the real migrations; it is dropped when the run ends.
 *
 * <p>The name carries the start time (rootstock_test_yyyyMMddHHmm_xxxxxx), so a
 * schema left by a killed run is easy to spot and drop by hand.
 */
public final class ThrowawaySchema implements AutoCloseable {

	public static final String PREFIX = "rootstock_test_";

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

	public static ThrowawaySchema create(Environment env) {
		String url = require(env, "DB_URL");
		String username = require(env, "DB_USERNAME");
		String password = require(env, "DB_PASSWORD");
		String name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + "_"
				+ UUID.randomUUID().toString().substring(0, 6);
		ThrowawaySchema schema = new ThrowawaySchema(name, url, username, password);
		schema.execute("CREATE SCHEMA " + name);
		return schema;
	}

	public String name() {
		return name;
	}

	/** Drops the schema and everything in it. Refuses any name it did not make. */
	@Override
	public void close() {
		if (!name.startsWith(PREFIX)) {
			throw new IllegalStateException("Refusing to drop " + name + ": not a test schema");
		}
		execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
	}

	private void execute(String sql) {
		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			connection.createStatement().execute(sql);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not run '" + sql + "' for test schema " + name, ex);
		}
	}

	private static String require(Environment env, String key) {
		String value = env.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(key + " is not set. Integration tests run against RDS: load the "
					+ "settings first (set -a && source .env && set +a).");
		}
		return value;
	}
}
