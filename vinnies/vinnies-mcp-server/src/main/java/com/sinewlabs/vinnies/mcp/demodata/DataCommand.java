package com.sinewlabs.vinnies.mcp.demodata;

import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The {@code seed} and {@code reset} commands: run the app with one of them as
 * its first argument and it loads the demo data and exits, instead of serving
 * MCP. See demo-data.sh.
 */
public enum DataCommand {

	SEED, RESET;

	private static final Logger log = LoggerFactory.getLogger(DataCommand.class);

	/** The command named by the first non-option argument, if any. */
	public static Optional<DataCommand> from(String[] args) {
		return Arrays.stream(args)
				.filter(a -> !a.startsWith("--"))
				.findFirst()
				.flatMap(a -> Arrays.stream(values()).filter(c -> c.name().equalsIgnoreCase(a)).findFirst());
	}

	public DemoDataLoader.Summary run(ConfigurableApplicationContext context) {
		DemoDataLoader loader = context.getBean(DemoDataLoader.class);
		DemoDataLoader.Summary summary = this == SEED ? loader.seed() : loader.reset();
		log.info("{}: {} -- households={} people={} assistance={} services={} guidelines={} fingerprint={}",
				name().toLowerCase(), summary.outcome(), summary.households(), summary.people(),
				summary.assistance(), summary.services(), summary.guidelines(), summary.fingerprint());
		return summary;
	}
}
