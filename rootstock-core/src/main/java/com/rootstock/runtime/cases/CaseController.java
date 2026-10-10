package com.rootstock.runtime.cases;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.cases.CaseDecisions;
import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.cases.CaseRun;
import com.rootstock.core.cases.CaseRunService;
import com.rootstock.core.cases.NoGraphException;
import com.rootstock.core.cases.RunEvent;
import com.rootstock.core.cases.RunRegistry;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.runtime.observability.LangfuseLinks;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Submitting a case, following its run, and deciding it where it waits for a
 * person. A run starts at once and goes on in the background; the screen
 * follows it through {@code /events}. A case is visible to the user who
 * submitted it, to admins, to whoever may decide it while it waits for a
 * decision, and to whoever last decided it: it holds a household's details.
 *
 * <p>The decision endpoints are generic: a client's own application (e.g. its
 * coordinators' review screen) lists the cases waiting for its user and posts
 * their decision; who may decide is checked here.
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

	static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

	private final CaseRunService service;
	private final RunRegistry runs;
	private final CaseGraphs graphs;
	private final LangfuseLinks langfuse;
	private final CaseDecisions decisions;

	public CaseController(CaseRunService service, RunRegistry runs, CaseGraphs graphs, LangfuseLinks langfuse,
			CaseDecisions decisions) {
		this.service = service;
		this.runs = runs;
		this.graphs = graphs;
		this.langfuse = langfuse;
		this.decisions = decisions;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	CaseViews.RunView submit(@Valid @RequestBody CaseViews.SubmitRequest request) {
		String simulate = request.simulate() == null || request.simulate().equals("none") ? null : request.simulate();
		try {
			CaseRun run = service.start(blankToNull(request.pack()), request.input(),
					simulate == null ? Map.of() : Map.of(StubNodes.SIMULATE, simulate), AuthContext.require().userId());
			return CaseViews.summary(run, traceUrl(run), waitingFor(run), caseStatus(run));
		}
		catch (NoGraphException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
		}
	}

	/** The signed-in user's cases (all cases, for an admin), newest first. */
	@GetMapping
	List<CaseViews.RunView> recent() {
		return runs.recent().stream().filter(this::ownOrAdmin).map(r -> CaseViews.summary(r, traceUrl(r), waitingFor(r), caseStatus(r)))
				.toList();
	}

	@GetMapping("/{caseId}")
	CaseViews.RunView detail(@PathVariable String caseId) {
		CaseRun run = find(caseId);
		return CaseViews.detail(run, traceUrl(run), waitingFor(run), caseStatus(run));
	}

	/**
	 * Server-sent events: every event so far, then each new one as it happens.
	 * Closes after the run pauses, completes or fails.
	 */
	@GetMapping(path = "/{caseId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(@PathVariable String caseId) {
		CaseRun run = find(caseId);
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
		AtomicReference<Runnable> unsubscribe = new AtomicReference<>(() -> {
		});
		emitter.onCompletion(() -> unsubscribe.get().run());
		emitter.onTimeout(() -> unsubscribe.get().run());
		emitter.onError(e -> unsubscribe.get().run());
		unsubscribe.set(run.subscribe(event -> send(emitter, event)));
		return emitter;
	}

	/** The cases waiting for a decision the signed-in user may make, oldest first. */
	@GetMapping("/awaiting-decision")
	List<CaseViews.RunView> awaitingDecision() {
		return decisions.awaiting(AuthContext.require()).stream()
				.map(r -> CaseViews.summary(r, traceUrl(r), waitingFor(r), caseStatus(r))).toList();
	}

	/**
	 * A person's decision on a case waiting before a human-review node: the run
	 * resumes with it (an edit goes back through validation). Only someone in the
	 * node's approverRoles, or an admin, may decide.
	 */
	@PostMapping("/{caseId}/decision")
	@ResponseStatus(HttpStatus.ACCEPTED)
	CaseViews.RunView decide(@PathVariable String caseId, @Valid @RequestBody CaseViews.DecisionRequest request) {
		find(caseId);
		try {
			CaseRun run = decisions.decide(caseId, request.decision(), request.record(), request.comment(),
					AuthContext.require());
			return CaseViews.summary(run, traceUrl(run), waitingFor(run), caseStatus(run));
		}
		catch (CaseDecisions.DecisionException e) {
			throw new ResponseStatusException(switch (e.reason()) {
				case NOT_FOUND -> HttpStatus.NOT_FOUND;
				case FORBIDDEN -> HttpStatus.FORBIDDEN;
				case CONFLICT -> HttpStatus.CONFLICT;
				case INVALID -> HttpStatus.BAD_REQUEST;
			}, e.getMessage());
		}
	}

	/** The graph the screen draws; the only one, unless a pack is named. */
	@GetMapping("/graph")
	CaseViews.GraphView graph(@RequestParam(required = false) String pack) {
		CaseGraph graph = (pack == null || pack.isBlank() ? graphs.only() : graphs.forPack(pack))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						pack == null ? "No single pack with a graph is configured" : "Pack '" + pack + "' has no graph"));
		return CaseViews.graph(graph);
	}

	private static void send(SseEmitter emitter, RunEvent event) {
		try {
			emitter.send(SseEmitter.event().id(String.valueOf(event.seq())).name("run").data(event,
					MediaType.APPLICATION_JSON));
			if (event.type().ends()) {
				emitter.complete();
			}
		}
		catch (IOException | IllegalStateException e) {
			// The screen went away; the run carries on regardless.
			emitter.completeWithError(e);
		}
	}

	private CaseRun find(String caseId) {
		return runs.byCase(caseId).filter(this::visible)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No case " + caseId));
	}

	private boolean visible(CaseRun run) {
		AuthContext.Principal user = AuthContext.require();
		return ownOrAdmin(run) || decisions.mayDecide(run.caseId(), user) || decidedBy(run, user);
	}

	private static boolean decidedBy(CaseRun run, AuthContext.Principal user) {
		return run.result().get(CaseState.REVIEW) instanceof Map<?, ?> review && user.userId().equals(review.get("by"));
	}

	/** The case's submitter (its later runs may be started by someone else, e.g. a coordinator). */
	private boolean ownOrAdmin(CaseRun run) {
		AuthContext.Principal user = AuthContext.require();
		String owner = service.caseFile(run.caseId()).map(f -> f.startedBy()).orElse(run.startedBy());
		return user.role() == UserRole.ADMIN || user.userId().equals(owner);
	}

	private List<String> waitingFor(CaseRun run) {
		return decisions.waitingFor(run.caseId()).orElse(null);
	}

	/** How the case stands, which may differ from its latest run's status (e.g. AWAITING_DECISION). */
	private String caseStatus(CaseRun run) {
		return service.caseFile(run.caseId()).map(f -> f.status()).orElse(run.status().name());
	}

	private String traceUrl(CaseRun run) {
		return run.traceId() == null ? null : langfuse.traceUrl(run.traceId());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
