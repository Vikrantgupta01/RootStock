package com.rootstock.autoconfig.pack;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code rootstock.packs.*}.
 *
 * @param paths folders holding domain packs, or one pack each; set with
 *              {@code ROOTSTOCK_PACKS_PATHS} (comma separated). Rootstock ships none.
 */
@ConfigurationProperties(prefix = "rootstock.packs")
public record PacksProperties(List<String> paths) {

	public PacksProperties {
		paths = paths == null ? List.of() : paths.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
	}
}
