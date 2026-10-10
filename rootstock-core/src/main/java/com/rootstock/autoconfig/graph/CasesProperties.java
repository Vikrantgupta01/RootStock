package com.rootstock.autoconfig.graph;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code rootstock.cases.*}.
 *
 * @param stubPause how long each stub node takes, so a run's progress is visible
 *                  on screen; goes away with the stubs
 */
@ConfigurationProperties(prefix = "rootstock.cases")
public record CasesProperties(@DefaultValue("600ms") Duration stubPause) {
}
