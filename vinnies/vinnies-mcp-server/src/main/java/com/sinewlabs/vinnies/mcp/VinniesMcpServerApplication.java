package com.sinewlabs.vinnies.mcp;

import com.sinewlabs.vinnies.mcp.demodata.DataCommand;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The dummy Vinnies application. It plays the client's existing system and
 * exposes its functions as MCP tools over Streamable HTTP at {@code /mcp}.
 * All data it holds is fictional.
 *
 * <p>Started with {@code seed} or {@code reset} as its first argument, it loads
 * the demo data and exits instead, without a web server, so it can run while the
 * server is up on the same port.
 */
@SpringBootApplication
public class VinniesMcpServerApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(VinniesMcpServerApplication.class);
		Optional<DataCommand> command = DataCommand.from(args);
		if (command.isEmpty()) {
			app.run(args);
			return;
		}
		app.setWebApplicationType(WebApplicationType.NONE);
		app.setDefaultProperties(Map.of("spring.ai.mcp.server.enabled", "false"));
		try (ConfigurableApplicationContext context = app.run(args)) {
			command.get().run(context);
		}
	}
}
