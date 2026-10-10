package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphValidatorTest {

	@TempDir
	Path tmp;

	private final GraphValidator validator = new GraphValidator();

	private List<String> problems(String file, UnaryOperator<String> edit) {
		Path dir = RepairsPack.copyWith(tmp.resolve("repairs"), file, s -> {
			String changed = edit.apply(s);
			assertThat(changed).as("the edit changed nothing").isNotEqualTo(s);
			return changed;
		});
		PackGraph pack = new PackGraphLoader().load("repairs", dir);
		return validator.validate(pack, RepairsPack.context()).stream().map(GraphProblem::toString).toList();
	}

	@Test
	void theRepairsGraphIsValid() {
		assertThat(validator.validate(RepairsPack.load(), RepairsPack.context())).isEmpty();
	}

	@Test
	void anUnknownNodeTypeListsTheKnownOnes() {
		assertThat(problems("graph.yaml", s -> s.replace("type: ingest", "type: intake"))).containsExactly(
				"graph.yaml nodes[0] (ingest): unknown node type 'intake'; known: [await-input, clarify, human-review, "
						+ "ingest, rules, tool-executor]");
	}

	@Test
	void anAgentMustNameAConfiguredModelProfile() {
		GraphValidator.Context base = RepairsPack.context();
		GraphValidator.Context profiles = new GraphValidator.Context(base.registry(), base.routers(), base.tools(),
				base.ontology(), null, java.util.Set.of("extraction"));

		assertThat(validator.validate(RepairsPack.load(), profiles)).extracting(GraphProblem::toString).containsExactly(
				"agents/property-lookup.yaml spec.model: unknown model profile 'fast'; configured (rootstock.llm.profiles): "
						+ "[extraction]");
	}

	@Test
	void aPlanMayOnlyUseTheAgentsOwnToolsAndReadKnownState() {
		assertThat(problems("agents/property-lookup.yaml", s -> s.replace(
				"{ tool: list_contractors, forEach: $.record.defects, with: { trade: $item.trade } }",
				"{ tool: book_visit, with: { trade: $item.trade, job: $.jobs.id } }"))).containsExactlyInAnyOrder(
						"agents/property-lookup.yaml spec.plan[0].tool: tool 'book_visit' is not in the agent's tools.allow "
								+ "[find_property, list_contractors]",
						"agents/property-lookup.yaml spec.plan[0].with: '$item.trade' reads $item, but the step has no forEach",
						"agents/property-lookup.yaml spec.plan[0]: '$.jobs.id' reads unknown state channel 'jobs'; known: "
								+ "[actions, audit, caseId, clarifyRounds, context, issues, options, pack, questions, rawInput, "
								+ "record, review, runId, traceId]");
	}

	@Test
	void anUnknownAgent() {
		assertThat(problems("graph.yaml", s -> s.replace("agent: job-extractor", "agent: job-reader")))
				.contains("graph.yaml nodes[1] (extract): unknown agent 'job-reader'; agents/ has [job-extractor, "
						+ "job-judge, property-lookup, question-writer, tenant-chaser, visit-planner]");
	}

	@Test
	void anEdgeToANodeThatDoesNotExist() {
		assertThat(problems("graph.yaml", s -> s.replace("{ from: draft,   to: review }", "{ from: draft,   to: reveiw }")))
				.contains("graph.yaml edges[6] (from draft): unknown target 'reveiw'");
	}

	@Test
	void aNodeThatCannotBeReached() {
		assertThat(problems("graph.yaml", s -> s.replace("{ when: { issues.anySeverity: BLOCKING }, to: chase }",
				"{ when: { issues.anySeverity: BLOCKING }, to: draft }")))
				.contains("graph.yaml node chase: cannot be reached from START");
	}

	@Test
	void aRouteWithoutADefault() {
		assertThat(problems("graph.yaml", s -> s.replace("      - { default: draft }\n", "")))
				.anySatisfy(p -> assertThat(p).endsWith("the route needs a 'default' branch, so a run never has nowhere to go"));
	}

	@Test
	void aConditionOnAnUnknownChannel() {
		assertThat(problems("graph.yaml", s -> s.replace("review.decision: APPROVED", "approval.decision: APPROVED")))
				.anySatisfy(p -> assertThat(p).contains("'approval.decision': unknown state channel 'approval'"));
	}

	@Test
	void aListTestOnAChannelThatIsNotAList() {
		assertThat(problems("graph.yaml", s -> s.replace("review.decision: EDITED", "review.anySeverity: EDITED")))
				.anySatisfy(p -> assertThat(p).contains("'anySeverity' works on a list channel; 'review' is not one"));
	}

	@Test
	void twoEdgesFromOneNode() {
		assertThat(problems("graph.yaml", s -> s.replace("  - { from: chase,   to: review }",
				"  - { from: chase,   to: review }\n  - { from: chase,   to: END }")))
				.contains("graph.yaml edges from chase: 2 edges leave this node; use one edge with a 'route' to branch");
	}

	@Test
	void anAgentWritingToAnUndeclaredChannel() {
		assertThat(problems("agents/visit-planner.yaml", s -> s.replace("writeTo: actions", "writeTo: proposals")))
				.containsExactly("agents/visit-planner.yaml spec.output.writeTo: unknown state channel 'proposals'; "
						+ "declare it under state.channels in graph.yaml");
	}

	@Test
	void anAgentWithAProjectionTheOntologyDoesNotHave() {
		assertThat(problems("agents/job-extractor.yaml", s -> s.replace("projection: job-intake", "projection: job-intakes")))
				.containsExactly("agents/job-extractor.yaml spec.output.projection: unknown projection 'job-intakes'; "
						+ "the ontology has [job-intake, dispatch-view]");
	}

	// --- safety invariants -----------------------------------------------------

	@Test
	void anAgentMayNotUseAWriteTool() {
		assertThat(problems("agents/property-lookup.yaml", s -> s.replace("allow: [find_property, list_contractors]",
				"allow: [find_property, book_visit]"))).contains(
						"agents/property-lookup.yaml spec.tools.allow: tool 'book_visit' writes; agents may only use read "
								+ "tools (writes happen in a tool-executor node, after review)",
						"agents/property-lookup.yaml spec.tools.allow: tool 'book_visit' is not on node 'enrich''s "
								+ "allowlist in tools.yaml ([find_property, list_contractors])");
	}

	@Test
	void anAgentsToolsMustBeOnItsNodesAllowlist() {
		RepairsPack.load();
		// The same agent used by a node with no allowlist: every tool is refused.
		assertThat(problems("graph.yaml", s -> s.replace("{ id: enrich,  agent: property-lookup }",
				"{ id: lookup,  agent: property-lookup }").replace("{ from: extract, to: enrich }", "{ from: extract, to: lookup }")
				.replace("{ from: enrich,  to: validate }", "{ from: lookup,  to: validate }")))
				.contains("agents/property-lookup.yaml spec.tools.allow: tool 'find_property' is not on node 'lookup''s "
						+ "allowlist in tools.yaml ([])");
	}

	@Test
	void onlyAToolExecutorMayWrite() {
		assertThat(problems("graph.yaml", s -> s.replace("config: { actionType: VISIT }",
				"config: { actionType: VISIT, allowWrites: true }")))
				.contains("graph.yaml node draft: only a tool-executor node may set allowWrites");
	}

	@Test
	void aWriteNodeWithoutReviewBeforeItIsRejected() {
		assertThat(problems("graph.yaml", s -> s.replace("{ from: draft,   to: review }", "{ from: draft,   to: commit }")))
				.contains("graph.yaml node commit: can be reached from START without passing a human-review node in "
						+ "runtime.interruptBefore; every write needs a human approval first (gates: [review])");
	}

	@Test
	void reviewThatDoesNotPauseIsNoGate() {
		assertThat(problems("graph.yaml", s -> s.replace("interruptBefore: [review, await_input]",
				"interruptBefore: [await_input]")))
				.contains("graph.yaml node commit: can be reached from START without passing a human-review node in "
						+ "runtime.interruptBefore; every write needs a human approval first (there is no such node)");
	}

	@Test
	void theWriteNodeInToolsYamlMustSayAllowWrites() {
		assertThat(problems("graph.yaml", s -> s.replace("config: { allowWrites: true }", "config: { allowWrites: false }")))
				.contains("graph.yaml node commit: tools.yaml lists it as a write node; set config.allowWrites: true to "
						+ "make that explicit here too");
	}
}
