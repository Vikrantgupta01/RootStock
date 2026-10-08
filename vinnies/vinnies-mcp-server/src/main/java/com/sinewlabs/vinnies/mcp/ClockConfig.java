package com.sinewlabs.vinnies.mcp;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock for the app, so tests can fix time instead of racing it. It runs on
 * Sydney time: "today", "the last 30 days" and the seed data's dates are all
 * Vinnies NSW days, wherever the server runs. Instants are unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	public static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

	@Bean
	Clock clock() {
		return Clock.system(SYDNEY);
	}
}
