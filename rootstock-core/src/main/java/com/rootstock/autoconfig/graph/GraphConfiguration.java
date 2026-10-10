package com.rootstock.autoconfig.graph;

import com.rootstock.core.cases.CaseDecisions;
import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.cases.CaseRunService;
import com.rootstock.core.cases.RunObserver;
import com.rootstock.core.cases.CaseStore;
import com.rootstock.core.cases.JdbcCaseStore;
import com.rootstock.core.cases.JdbcRunStore;
import com.rootstock.core.cases.RunRegistry;
import com.rootstock.core.cases.RunStore;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseRouter;
import com.rootstock.core.graph.GraphCompiler;
import com.rootstock.core.graph.GraphDefinitionException;
import com.rootstock.core.graph.GraphProblem;
import com.rootstock.core.graph.GraphValidator;
import com.rootstock.core.graph.JdbcCheckpointSaver;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.NodeRegistry;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.PackGraphLoader;
import com.rootstock.core.graph.nodes.DrafterAgent;
import com.rootstock.core.graph.nodes.HumanReviewNode;
import com.rootstock.core.graph.nodes.IngestNode;
import com.rootstock.core.graph.nodes.JudgeAgent;
import com.rootstock.core.graph.nodes.RulesNode;
import com.rootstock.core.graph.nodes.StructuredExtraction;
import com.rootstock.core.graph.nodes.ToolCallingAgent;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleKinds;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads, checks and compiles the graph of every pack that has a
 * {@code graph.yaml}. Unlike a broken ontology, which is only listed, a graph or
 * agent that is broken or unsafe stops startup with every problem listed: it
 * decides what Rootstock does with a case and when it may write to a client
 * system.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CasesProperties.class)
public class GraphConfiguration {

	private static final Logger log = LoggerFactory.getLogger(GraphConfiguration.class);

	/**
	 * Every building block: the stubs, replaced type by type by any real
	 * {@link NodeFactory} bean as later iterations add them.
	 */
	@Bean
	NodeRegistry nodeRegistry(CasesProperties properties, ObjectProvider<NodeFactory> real) {
		Map<String, NodeFactory> byType = new LinkedHashMap<>();
		StubNodes.all(properties.stubPause()).forEach(f -> byType.put(f.kind() + ":" + f.type(), f));
		real.orderedStream().forEach(f -> byType.put(f.kind() + ":" + f.type(), f));
		return new NodeRegistry(byType.values());
	}

	@Bean
	IngestNode ingestNode() {
		return new IngestNode();
	}

	@Bean
	Map<String, RuleKind> ruleKinds(ObjectProvider<RuleKind> added) {
		return RuleKinds.of(added.orderedStream().toList());
	}

	/** Finds the judge's factory through the registry, which is only built once every factory exists. */
	@Bean
	RulesNode rulesNode(Map<String, RuleKind> ruleKinds, ObjectProvider<NodeRegistry> registry) {
		return new RulesNode(ruleKinds, type -> registry.getObject().agent(type), Clock.systemDefaultZone());
	}

	@Bean
	JudgeAgent judgeAgent(LlmService llm, PromptRegistry prompts) {
		return new JudgeAgent(llm, prompts, Clock.systemDefaultZone());
	}

	@Bean
	StructuredExtraction structuredExtraction(LlmService llm, PromptRegistry prompts) {
		return new StructuredExtraction(llm, prompts, Clock.systemDefaultZone());
	}

	@Bean
	ToolCallingAgent toolCallingAgent(LlmService llm, PromptRegistry prompts, ToolGateway gateway) {
		return new ToolCallingAgent(llm, prompts, gateway, Clock.systemDefaultZone());
	}

	@Bean
	HumanReviewNode humanReviewNode() {
		return new HumanReviewNode();
	}

	@Bean
	DrafterAgent drafterAgent(LlmService llm, PromptRegistry prompts) {
		return new DrafterAgent(llm, prompts, Clock.systemDefaultZone());
	}

	/** Checkpoints for graphs with {@code runtime.checkpointer: postgres}. */
	@Bean
	JdbcCheckpointSaver jdbcCheckpointSaver(JdbcTemplate jdbc) {
		return new JdbcCheckpointSaver(jdbc);
	}

	@Bean
	CaseStore caseStore(JdbcTemplate jdbc) {
		return new JdbcCaseStore(jdbc);
	}

	@Bean
	RunStore runStore(JdbcTemplate jdbc) {
		return new JdbcRunStore(jdbc, JsonMapper.builder().build());
	}

	@Bean
	RunRegistry runRegistry(ObjectProvider<RunObserver> observers, RunStore store) {
		RunRegistry runs = new RunRegistry(observers.orderedStream().toList(), store);
		int interrupted = runs.failInterrupted();
		if (interrupted > 0) {
			log.warn("{} run(s) were in progress when Rootstock last stopped; marked FAILED", interrupted);
		}
		return runs;
	}

	@Bean
	CaseDecisions caseDecisions(CaseRunService service, RunRegistry runs, CaseGraphs graphs) {
		return new CaseDecisions(service, runs, graphs, Clock.systemUTC());
	}

	@Bean
	CaseGraphs caseGraphs(PackRegistry packs, NodeRegistry registry, ObjectProvider<CaseRouter> routerBeans,
			ToolCatalog tools, RunRegistry runs, LlmService llm, Map<String, RuleKind> ruleKinds,
			JdbcCheckpointSaver checkpoints) {
		List<CaseRouter> routers = routerBeans.orderedStream().toList();
		GraphCompiler compiler = new GraphCompiler(registry, routers, runs, checkpoints);
		PackGraphLoader loader = new PackGraphLoader();
		GraphValidator validator = new GraphValidator();
		List<CaseGraph> graphs = new ArrayList<>();
		for (LoadedPack pack : packs.snapshot().packs()) {
			Path dir = Path.of(pack.location());
			if (!PackGraphLoader.hasGraph(dir)) {
				continue;
			}
			List<PackGraph> definitions = loader.loadAll(pack.name(), dir);
			ResolvedOntology ontology = packs.ontology(pack.name()).orElse(null);
			String missing = switch (pack.status()) {
				case VALID -> null;
				case NO_ONTOLOGY -> "the pack has no ontology.yaml";
				case INVALID -> "the pack's ontology.yaml is invalid (" + pack.problems().size() + " problem(s))";
			};
			List<GraphProblem> problems = validator.validatePack(definitions, new GraphValidator.Context(registry,
					new HashSet<>(routers.stream().map(CaseRouter::name).toList()), tools, ontology, missing, llm.profiles(), ruleKinds));
			if (!problems.isEmpty()) {
				throw new GraphDefinitionException("Pack '" + pack.name() + "' has an invalid graph", problems);
			}
			for (PackGraph definition : definitions) {
				graphs.add(compiler.compile(definition, ontology));
				var g = definition.graph();
				log.info("Graph '{}' {} of pack '{}' (trigger {}{}): {} nodes, {} agents; pauses before {}, after {}",
						g.metadata().name(), g.metadata().version(), pack.name(), g.trigger().kind(),
						g.trigger().approverRoles().isEmpty() ? "" : " by " + g.trigger().approverRoles(),
						g.nodes().size(), definition.agents().size(), g.runtime().interruptBefore(),
						g.runtime().interruptAfter());
			}
		}
		if (graphs.isEmpty()) {
			log.info("No pack has a graph.yaml: cases cannot be submitted");
		}
		return new CaseGraphs(graphs);
	}

	@Bean(destroyMethod = "close")
	CaseRunService caseRunService(CaseGraphs graphs, RunRegistry runs, CaseStore cases) {
		return new CaseRunService(graphs, runs, cases);
	}
}
