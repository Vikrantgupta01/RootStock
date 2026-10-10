package com.rootstock.core.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseRouter;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphCompiler;
import com.rootstock.core.graph.GraphValidator;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.NodeRegistry;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.PackGraphLoader;
import com.rootstock.core.graph.ProposedAction;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.graph.nodes.IngestNode;
import com.rootstock.core.graph.nodes.RulesNode;
import com.rootstock.core.graph.nodes.StructuredExtraction;
import com.rootstock.core.graph.nodes.StructuredExtractionTest;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.ScriptedChatModel;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseRunServiceTest {

	@TempDir
	Path tmp;

	private final List<String> observed = new CopyOnWriteArrayList<>();
	private CaseRunService service;

	private final RunObserver observer = new RunObserver() {

		@Override
		public void runStarted(CaseRun run, String input) {
			observed.add("run-started");
		}

		@Override
		public void nodeStarted(CaseRun run, String node) {
			observed.add("start " + node);
		}

		@Override
		public void nodeFinished(CaseRun run, String node, Map<String, Object> update) {
			observed.add("end " + node);
		}

		@Override
		public void nodeFailed(CaseRun run, String node, Throwable failure) {
			observed.add("fail " + node);
		}

		@Override
		public void runEnded(CaseRun run) {
			observed.add("run-" + run.status().name().toLowerCase());
		}
	};

	private CaseRunService service(PackGraph pack, NodeRegistry registry, List<CaseRouter> routers) {
		assertThat(new GraphValidator().validate(pack, new GraphValidator.Context(registry,
				Set(routers), RepairsPack.tools(), RepairsPack.ontology(), null))).isEmpty();
		RunRegistry runs = new RunRegistry(List.of(observer));
		CaseGraph graph = new GraphCompiler(registry, routers, runs).compile(pack, RepairsPack.ontology());
		service = new CaseRunService(new CaseGraphs(List.of(graph)), runs);
		return service;
	}

	private static java.util.Set<String> Set(List<CaseRouter> routers) {
		return new java.util.HashSet<>(routers.stream().map(CaseRouter::name).toList());
	}

	@AfterEach
	void close() {
		if (service != null) {
			service.close();
		}
	}

	private static CaseRun finished(CaseRun run) throws InterruptedException {
		for (int i = 0; i < 200 && run.status() == CaseRun.Status.RUNNING; i++) {
			Thread.sleep(25);
		}
		assertThat(run.status()).as("run still going").isNotEqualTo(CaseRun.Status.RUNNING);
		return run;
	}

	private static List<String> nodesRun(CaseRun run) {
		return run.events().stream().filter(e -> e.type() == RunEvent.Type.NODE_FINISHED).map(RunEvent::node).toList();
	}

	@Test
	@SuppressWarnings("unchecked")
	void aCleanCaseRunsToReviewAndPausesBeforeIt() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), RepairsPack.registry(), List.of())
				.start(null, "  Kitchen tap leaking since Monday  ", Map.of(), "user-1"));

		assertThat(run.status()).isEqualTo(CaseRun.Status.PAUSED);
		assertThat(run.pause()).isEqualTo(new CaseRun.Pause("review", true));
		assertThat(nodesRun(run)).containsExactly("ingest", "extract", "enrich", "validate", "draft");
		assertThat(run.events().getFirst().type()).isEqualTo(RunEvent.Type.RUN_STARTED);
		assertThat(run.events().getLast().type()).isEqualTo(RunEvent.Type.RUN_PAUSED);

		Map<String, Object> result = run.result();
		Map<String, Object> record = (Map<String, Object>) result.get("record");
		assertThat(record).containsOnlyKeys("priority", "hazards", "reportedOn", "tenant", "defects", "visits", "propertyRef");
		assertThat((List<Object>) result.get("actions")).containsExactly(new ProposedAction("VISIT", "Stub visit for review"));
		assertThat((List<?>) result.get("audit")).hasSize(5).first()
				.isEqualTo(new AuditEntry("ingest", "Received 5 words of input"));
		assertThat(result).doesNotContainKeys(CaseState.RAW_INPUT, CaseState.OPTIONS);
		assertThat(observed).containsExactly("run-started", "start ingest", "end ingest", "start extract", "end extract",
				"start enrich", "end enrich", "start validate", "end validate", "start draft", "end draft", "run-paused");
	}

	@Test
	void anIssueTheSubmitterCanAnswerGoesToClarifyAndPausesAfterIt() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), RepairsPack.registry(), List.of())
				.start(null, "Tap leaking", Map.of(StubNodes.SIMULATE, "clarify"), "user-1"));

		assertThat(run.pause()).isEqualTo(new CaseRun.Pause("clarify", false));
		assertThat(nodesRun(run)).containsExactly("ingest", "extract", "enrich", "validate", "clarify");
		assertThat(run.result()).containsEntry(CaseState.CLARIFY_ROUNDS, 1);
		assertThat((List<?>) run.result().get("issues")).singleElement()
				.extracting("answerableBy").isEqualTo(CaseIssue.SUBMITTER);
	}

	@Test
	void anIssueOnlySomeoneElseCanAnswerIsChasedThenReviewed() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), RepairsPack.registry(), List.of())
				.start(null, "Tap leaking", Map.of(StubNodes.SIMULATE, "chase"), "user-1"));

		assertThat(run.pause()).isEqualTo(new CaseRun.Pause("review", true));
		assertThat(nodesRun(run)).containsExactly("ingest", "extract", "enrich", "validate", "chase");
		assertThat((List<?>) run.result().get("actions")).singleElement().extracting("type")
				.isEqualTo(ProposedAction.CHASE_MESSAGE);
	}

	@Test
	void aNamedRouterDecidesWhereConditionsCannot() throws Exception {
		Path dir = RepairsPack.copyWith(tmp.resolve("repairs"), "graph.yaml", s -> s.replaceFirst(
				"(?s)  - from: validate\n    route:.*?\\{ default: draft \\}\n",
				"  - { from: validate, router: by-priority, targets: [clarify, chase, draft] }\n"));
		CaseRouter byPriority = new CaseRouter() {

			@Override
			public String name() {
				return "by-priority";
			}

			@Override
			public String route(CaseState state) {
				return "chase";
			}
		};

		CaseRun run = finished(service(new PackGraphLoader().load("repairs", dir), RepairsPack.registry(),
				List.of(byPriority)).start(null, "Tap leaking", Map.of(), "user-1"));

		assertThat(nodesRun(run)).containsExactly("ingest", "extract", "enrich", "validate", "chase");
	}

	@Test
	void aFailingNodeFailsTheRunWithItsReason() throws Exception {
		List<NodeFactory> factories = new ArrayList<>(StubNodes.all(Duration.ZERO).stream()
				.filter(f -> !f.type().equals("rules")).toList());
		factories.add(new NodeFactory() {

			@Override
			public Kind kind() {
				return Kind.NODE;
			}

			@Override
			public String type() {
				return "rules";
			}

			@Override
			public NodeAction<CaseState> create(NodeContext context) {
				return state -> {
					throw new IllegalStateException("rules engine unavailable");
				};
			}
		});

		CaseRun run = finished(service(RepairsPack.load(), new NodeRegistry(factories), List.of())
				.start(null, "Tap leaking", Map.of(), "user-1"));

		assertThat(run.status()).isEqualTo(CaseRun.Status.FAILED);
		assertThat(run.error()).isEqualTo("rules engine unavailable");
		assertThat(run.events()).extracting(RunEvent::type).contains(RunEvent.Type.NODE_FAILED)
				.last().isEqualTo(RunEvent.Type.RUN_FAILED);
		assertThat(observed).contains("fail validate", "run-failed");
	}

	/** The stubs, with the real ingest, rules and extraction over a scripted model. */
	private static NodeRegistry realExtraction(ScriptedChatModel model) {
		PromptRegistry prompts = new PromptRegistry(null, Duration.ofMinutes(5), Clock.systemUTC());
		prompts.addBundled(BundledPrompts.load(RepairsPack.dir()));
		List<NodeFactory> real = List.of(new IngestNode(), new RulesNode(),
				new StructuredExtraction(model.service(), prompts, Clock.systemUTC()));
		List<NodeFactory> factories = new ArrayList<>(StubNodes.all(Duration.ZERO).stream()
				.filter(f -> real.stream().noneMatch(r -> r.kind() == f.kind() && r.type().equals(f.type()))).toList());
		factories.addAll(real);
		return new NodeRegistry(factories);
	}

	@Test
	void aRequiredFieldTheInputDoesNotGiveBecomesAClarifyQuestionNotAMadeUpValue() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), realExtraction(new ScriptedChatModel(
				StructuredExtractionTest.VALID)), List.of()).start(null, "Water everywhere in the kitchen", Map.of(),
						"user-1"));

		assertThat(run.pause()).isEqualTo(new CaseRun.Pause("clarify", false));
		assertThat(nodesRun(run)).containsExactly("ingest", "extract", "enrich", "validate", "clarify");
		assertThat(run.result().get("record")).asInstanceOf(InstanceOfAssertFactories.MAP)
				.containsEntry("reportedOn", null);
		// The repairs pack's own rule also asks for the urgent job's phone number.
		assertThat(run.result().get("issues")).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly(
				new CaseIssue(RulesNode.REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "Missing reportedOn",
						"reportedOn", CaseIssue.STRUCTURAL),
				new CaseIssue("urgent-needs-phone", CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
						"tenant.phone is required here", "tenant.phone", CaseIssue.BUSINESS));
		assertThat(run.input()).isEqualTo("Water everywhere in the kitchen");
	}

	@Test
	void outputThatNeverMatchesTheSchemaParksTheCase() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), realExtraction(new ScriptedChatModel(
				StructuredExtractionTest.WRONG_CODE)), List.of()).start(null, "Tap leaking", Map.of(), "user-1"));

		assertThat(run.status()).isEqualTo(CaseRun.Status.PARKED);
		assertThat(run.error()).contains("did not match 'job-intake' after 3 attempt(s)");
		assertThat(run.events().getLast().type()).isEqualTo(RunEvent.Type.RUN_PARKED);
		assertThat(observed).contains("fail extract", "run-parked");
	}

	@Test
	void aLateSubscriberGetsEverythingSoFarThenTheRest() throws Exception {
		CaseRun run = finished(service(RepairsPack.load(), RepairsPack.registry(), List.of())
				.start(null, "Tap leaking", Map.of(), "user-1"));
		List<RunEvent> seen = new ArrayList<>();

		run.subscribe(seen::add);

		assertThat(seen).isEqualTo(run.events());
		assertThat(Stream.of(seen.getLast().type())).containsExactly(RunEvent.Type.RUN_PAUSED);
	}
}
