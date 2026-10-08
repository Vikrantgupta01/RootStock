package com.sinewlabs.vinnies.mcp.guideline;

import com.sinewlabs.vinnies.mcp.security.Scopes;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client's assistance guidelines, served two ways from the same rows: as the
 * tool get_assistance_guidelines (structured: the limit and window the rules
 * use) and as MCP resources (the text reviewers and the judge prompt read). One
 * renderer for both, so the two can never disagree.
 */
@Component
public class GuidelinesTool {

	static final String RESOURCE_ROOT = "vinnies://guidelines";

	private final AssistanceGuidelineRepository guidelines;

	public GuidelinesTool(AssistanceGuidelineRepository guidelines) {
		this.guidelines = guidelines;
	}

	public record Guideline(NeedCategory assistanceType, String title, String guideline, BigDecimal limitPerVisitAud,
			int repeatWindowDays, LocalDate effectiveFrom, String resourceUri) {
	}

	@McpTool(name = "get_assistance_guidelines",
			description = "The Vinnies guideline for one type of assistance: the guideline text, the most that may "
					+ "be given per visit (limitPerVisitAud) and the repeat window in days (assistance of the same "
					+ "type within this many days counts as a repeat request). Use it to check proposed assistance "
					+ "against the client's current rules. The same text is published as the MCP resource in "
					+ "resourceUri.",
			annotations = @McpAnnotations(title = "Get assistance guidelines", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public Guideline getAssistanceGuidelines(
			@McpToolParam(description = "Type of assistance: FOOD, ENERGY_BILL or RENT.") NeedCategory assistanceType) {
		if (assistanceType == null) {
			throw new IllegalArgumentException("assistanceType is required: FOOD, ENERGY_BILL or RENT");
		}
		return toGuideline(find(assistanceType));
	}

	@McpResource(uri = RESOURCE_ROOT + "/{assistanceType}", name = "assistance-guideline",
			title = "Assistance guideline", mimeType = "text/markdown",
			description = "The Vinnies guideline for one type of assistance (FOOD, ENERGY_BILL or RENT), "
					+ "including its limit per visit and repeat window.")
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public String guidelineResource(String assistanceType) {
		NeedCategory type;
		try {
			type = NeedCategory.valueOf(assistanceType.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unknown assistance type " + assistanceType
					+ "; expected FOOD, ENERGY_BILL or RENT");
		}
		return render(find(type));
	}

	@McpResource(uri = RESOURCE_ROOT, name = "assistance-guidelines", title = "All assistance guidelines",
			mimeType = "text/markdown",
			description = "Every Vinnies assistance guideline in one document: text, limits and repeat windows.")
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public String allGuidelinesResource() {
		List<AssistanceGuideline> all = guidelines.findAllByOrderByAssistanceTypeAsc();
		return all.stream().map(GuidelinesTool::render).collect(Collectors.joining("\n\n---\n\n"));
	}

	private AssistanceGuideline find(NeedCategory type) {
		return guidelines.findById(type)
				.orElseThrow(() -> new IllegalStateException("No guideline recorded for " + type));
	}

	static Guideline toGuideline(AssistanceGuideline g) {
		return new Guideline(g.getAssistanceType(), g.getTitle(), g.getGuidelineText(), g.getLimitPerVisitAud(),
				g.getRepeatWindowDays(), g.getEffectiveFrom(), RESOURCE_ROOT + "/" + g.getAssistanceType());
	}

	/** The resource text: the same facts as the tool, as a short Markdown document. */
	static String render(AssistanceGuideline g) {
		return "# " + g.getTitle() + "\n\n"
				+ "- Assistance type: " + g.getAssistanceType() + "\n"
				+ "- Limit per visit: $" + g.getLimitPerVisitAud().toPlainString() + "\n"
				+ "- Repeat window: " + g.getRepeatWindowDays() + " days\n"
				+ "- Effective from: " + g.getEffectiveFrom() + "\n\n"
				+ g.getGuidelineText();
	}
}
