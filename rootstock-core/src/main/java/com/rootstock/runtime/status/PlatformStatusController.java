package com.rootstock.runtime.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform status for the demo screen: is every service Rootstock depends on
 * answering right now. For any signed-in user (it reveals no records); each call
 * makes one very small Bedrock request, so it runs on demand, not on a timer.
 */
@RestController
@RequestMapping("/api/status")
public class PlatformStatusController {

	private final PlatformStatusService status;

	public PlatformStatusController(PlatformStatusService status) {
		this.status = status;
	}

	@GetMapping
	public PlatformStatusService.Status status() {
		return status.check();
	}
}
