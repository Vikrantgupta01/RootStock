package com.sinewlabs.vinnies.mcp.localservice;

import com.sinewlabs.vinnies.mcp.localservice.LocalServiceSearch.ServiceMatch;
import com.sinewlabs.vinnies.mcp.security.Scopes;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * search_local_services: referral candidates for a need in a suburb (tool
 * catalogue: Read; returns address, hours, eligibility). Feeds the referral
 * suggestions in draft.
 */
@Component
public class SearchLocalServicesTool {

	private final LocalServiceRepository services;

	public SearchLocalServicesTool(LocalServiceRepository services) {
		this.services = services;
	}

	public record LocalServices(NeedCategory needType, String suburb, List<ServiceMatch> services) {
	}

	@McpTool(name = "search_local_services",
			description = "Local services a household can be referred to for one need, in or near a suburb. "
					+ "Returns up to 5 services with name, address, phone, opening hours and eligibility. Services "
					+ "in the requested suburb come first (inSuburb = true). If the suburb has none, services for "
					+ "the same need elsewhere are returned with inSuburb = false: say so when suggesting them. "
					+ "Check eligibility before recommending a service.",
			annotations = @McpAnnotations(title = "Search local services", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public LocalServices searchLocalServices(
			@McpToolParam(description = "The need to refer for: FOOD, ENERGY_BILL or RENT.") NeedCategory needType,
			@McpToolParam(description = "Suburb the household lives in, e.g. 'Blacktown'.") String suburb) {
		if (needType == null) {
			throw new IllegalArgumentException("needType is required: FOOD, ENERGY_BILL or RENT");
		}
		if (suburb == null || suburb.isBlank()) {
			throw new IllegalArgumentException("suburb is required");
		}
		return new LocalServices(needType, suburb.strip(),
				LocalServiceSearch.search(services.findByNeedCategory(needType), needType, suburb));
	}
}
