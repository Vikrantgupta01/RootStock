# Sinew Rootstock — Design (Vinnies Demo)

Oct 3, 2026 · @Viks

## Purpose and goals

Build one domain-agnostic agentic framework, Rootstock, on LangGraph4j and Spring AI, and prove it with a first domain pack: case intake for a dummy St Vincent de Paul (Vinnies) application that exposes its functions as MCP tools.

**Naming:** the product is sold as **Sinew Rootstock**. In code, modules and configuration it is simply **Rootstock**: `rootstock-core`, `rootstock-spring-boot-starter`, `rootstock-runtime`, the `rootstock/v1` YAML API and the `rootstock-core` ontology. Domain packs and connectors keep domain names (`vinnies-pack`, `vinnies-mcp-server`).

**Vision:** Sinew Labs strengthens AI transformation through connection. A sinew connects muscle to bone so strength becomes movement; Rootstock does the same for AI, connecting it to the client's systems through MCP, to their people through human review, and to their meaning through a shared ontology. The architecture below is that idea made concrete.

The framework captures the work every target client repeats: **read** documents and notes, **check** them against rules and history, **fill** records in the client's system, and **chase** people for missing information. A new industry (insurance brokers, customs brokers) should need only a new domain pack and a new MCP connector, not new framework code.

**Goals for the first build**

1. A working end-to-end demo: volunteer visit notes in, reviewed case record, referral and follow-up out.
2. A clean split between `rootstock-core` (framework) and `vinnies-pack` (domain), so the same graph runs for another domain with configuration only.
3. Production habits from day one: tracing and evals in Langfuse, durable checkpoints in Postgres, Cognito-secured access, human approval before any write.

**Design principles**

- **The client's system stays the system of record.** The agent reads and writes only through MCP tools the client application exposes.
- **Deterministic where it can be, LLM where it must be.** Hard rules (limits, mandatory fields) run as code; the LLM handles messy language, extraction and drafting.
- **Human in control.** Every write waits for an approval step, and every decision is traceable.
- **Configuration over code.** Schemas, rules, prompts and tool bindings live in the domain pack.

## Use cases

The demo covers four Vinnies use cases that run as one connected flow, plus one optional knowledge use case. Each uses the same core capabilities, which is what makes the framework reusable.

| ID | Use case | Trigger | Agent output | Core capabilities |
| --- | --- | --- | --- | --- |
| UC1 | Visit notes to case record | Volunteer submits free-text or voice-transcribed notes after a home visit | Structured case record: household, needs, assistance given, urgency | Read, Fill |
| UC2 | Guideline and eligibility check | UC1 record is extracted | List of issues: missing fields, assistance over limits, repeat requests, risk flags | Check |
| UC3 | Referral suggestions | Needs identified in UC1 | Ranked referrals to local services with reasons | Read, Check |
| UC4 | Missing info and follow-up | UC2 finds gaps, or the case is approved | Drafted message to the volunteer or household and a scheduled follow-up | Chase, Fill |
| UC5 (optional) | Volunteer procedures Q&A | Volunteer asks a question | Answer grounded in procedure documents, with citations | Read |

**How the flow runs:** UC1 extracts the record, UC2 checks it, UC3 adds referrals, and a coordinator approves or edits the result. On approval the agent writes the case, referrals and follow-up back to the Vinnies app. When information is missing, UC4 drafts the request instead and the case waits.

**The same capabilities in the next domains**

| Core capability | Vinnies | Insurance broker | Customs broker |
| --- | --- | --- | --- |
| Read | Visit notes | Quotes, policy schedules, client emails | Commercial invoice, packing list, bill of lading |
| Check | Assistance guidelines and history | Issued policy against quote and last year | Entry data against documents and past entries |
| Fill | Case record, referral | Renewal submission, comparison report | Import declaration draft, product library |
| Chase | Missing details, follow-up visit | Client renewal questionnaire | Missing documents from importer |

## Architecture overview

The agent runtime owns the workflow; the dummy Vinnies app owns the data and is reached only through MCP tools, exactly as a real client system would be.

&#91;embedded content: System architecture · agent runtime, MCP connector, AWS and LLMOps services\]

The highlighted ToolGateway is the single path into the client system, so permissions, approvals and idempotency are enforced in one place.

**Request flow for one case**

1. A volunteer submits notes through the demo UI; Cognito authenticates the call.
2. The runtime starts a graph run, checkpoints it in Postgres and opens a Langfuse trace.
3. Extract and judge steps call Bedrock through `LlmService`; enrich calls read tools on the MCP server.
4. The graph pauses at review; the coordinator approves or edits through the UI.
5. Commit calls write tools with an approval id; the MCP server writes to its own database.
6. Every step, tool call and decision is in the audit log and the Langfuse trace.

## Base framework (rootstock-core)

`rootstock-core` provides one standard case-processing graph built on LangGraph4j, generic nodes, and a small set of interfaces that a domain pack implements. It knows nothing about Vinnies, insurance or customs.

**Agent style:** a structured workflow graph, not a free-roaming ReAct agent. The LLM works inside specific nodes (extract, judge, draft), and the graph controls order, branching and approval. This keeps behaviour predictable, testable and auditable, which matters for clients handling sensitive data. A bounded tool-calling loop (max 3 to 5 iterations) is allowed inside the enrich node for open-ended lookups.

### Standard graph

1. **ingest** normalises input (text, transcript, attachments) and assigns a case and trace id.
2. **extract** turns input (plus any clarification answers or new information) into the domain record using an LLM with structured output against the schema generated from the ontology. Output that fails the schema is retried, then parked for a human.
3. **enrich** calls read-only MCP tools (history, guidelines, reference data) defined in the pack's context plan.
4. **validate** runs the four validation layers below and collects issues with severity and who can resolve them.
5. **route** sends the case to **clarify** when a blocking issue can be answered by the submitter, to **chase** when only an external party can answer, otherwise to **draft**.
6. **clarify** generates specific questions from the blocking issues and pauses for the submitter. Their answers are merged into the input and the case goes back through extract. After the configured maximum rounds (default 2), the case continues to draft with the issues still flagged.
7. **draft** prepares the proposed actions: final record, referrals, follow-up, messages.
8. **chase** drafts a request to an external party (for example the household) and sends it to review.
9. **review** is a human-in-the-loop interrupt. The graph pauses, a reviewer approves, edits or rejects.
10. **commit** executes the approved write actions through MCP tools with idempotency keys and records the audit trail. If the approved action was a chase message, the case moves to await\_input; otherwise it ends.
11. **await\_input** parks the case until new information arrives through `POST /cases/{id}/input`, then resumes into extract.

An edited draft loops back to **validate**, so reviewer changes are checked by the same layers.

**Validation layers.** A record can match the schema and still be wrong, so validation runs in four layers, cheapest and most certain first:

| Layer | Checks | Where it runs | Source |
| --- | --- | --- | --- |
| Structural | Valid JSON, types, required fields, allowed vocabulary values | extract (on LLM output) and validate (after reviewer edits) | JSON schema generated from the ontology |
| Semantic | Relations and cardinality (a Case concerns one Household and identifies at least one Need), ontology constraints | validate | `ontology.yaml` |
| Business | Limits, frequency, consent, urgent follow-up | validate | Pack rules, thresholds from the guidelines tool |
| Judgment | Record is consistent with the original notes and nothing is invented | validate | Consistency judge agent |

Each issue records its severity and who can resolve it (`SUBMITTER` or `EXTERNAL`), which drives the clarify-or-chase routing.

### Domain pack SPI

```java
public interface DomainPack {
    String id();                         // "vinnies-intake"
    Class<?> recordType();               // target of extraction, e.g. CaseRecord
    PromptRefs prompts();                // Langfuse prompt names: extract, judge, draft, chase
    List<Rule> rules();                  // deterministic checks, run in validate
    ContextPlan contextPlan();           // read tools to call in enrich + argument mapping
    ActionPlan actionPlan();             // write tools to call in commit
    ChasePolicy chasePolicy();           // which issues block, who to ask, channel
}

public interface Rule {
    String id();
    Optional<Issue> evaluate(CaseContext ctx);   // pure function, no LLM
}
```

### Graph state

```java
public class CaseState extends AgentState {
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
        "issues",  Channels.appender(ArrayList::new),
        "actions", Channels.appender(ArrayList::new),
        "audit",   Channels.appender(ArrayList::new));

    public CaseState(Map<String, Object> init) { super(init); }

    public Optional<String> caseId()        { return value("caseId"); }
    public Optional<String> rawInput()      { return value("rawInput"); }
    public Optional<Object> record()        { return value("record"); }
    public Optional<Map<String,Object>> context() { return value("context"); }
    public List<Issue> issues()             { return this.<List<Issue>>value("issues").orElse(List.of()); }
    public Optional<ReviewDecision> review(){ return value("review"); }
}
```

### Graph wiring

```java
var graph = new StateGraph<>(CaseState.SCHEMA, CaseState::new)
    .addNode("ingest",      node_async(ingestNode))
    .addNode("extract",     node_async(extractNode))
    .addNode("enrich",      node_async(enrichNode))
    .addNode("validate",    node_async(validateNode))
    .addNode("clarify",     node_async(clarifyNode))
    .addNode("draft",       node_async(draftNode))
    .addNode("chase",       node_async(chaseNode))
    .addNode("review",      node_async(reviewNode))
    .addNode("commit",      node_async(commitNode))
    .addNode("await_input", node_async(awaitInputNode))
    .addEdge(START, "ingest")
    .addEdge("ingest", "extract")
    .addEdge("extract", "enrich")
    .addEdge("enrich", "validate")
    .addConditionalEdges("validate", edge_async(router::afterValidate),
        Map.of("draft", "draft",
               "clarify", "clarify",          // submitter can answer now
               "chase", "chase"))             // only an external party can answer
    .addEdge("clarify", "extract")             // answers merged, re-extract
    .addEdge("draft", "review")
    .addEdge("chase", "review")
    .addConditionalEdges("review", edge_async(router::afterReview),
        Map.of("commit", "commit", "revalidate", "validate", "reject", END))
    .addConditionalEdges("commit", edge_async(router::afterCommit),
        Map.of("await", "await_input",         // chase message sent, park the case
               "done", END))
    .addEdge("await_input", "extract");        // new information arrives, re-process

var app = graph.compile(CompileConfig.builder()
    .checkpointSaver(postgresSaver)            // durable pause/resume
    .interruptAfter("clarify")                  // questions shown, wait for submitter answers
    .interruptBefore("review", "await_input")   // human approval; parked until new input
    .build());
```

The API calls above are indicative; confirm names against the LangGraph4j version you pin.

**Resuming after review:** the reviewer's decision is written into the paused thread with `app.updateState(config, Map.of("review", decision))`, then the graph resumes from the checkpoint. Because checkpoints are in Postgres, a case can wait days for a coordinator without holding any memory.

### Declarative graphs and agents (YAML)

The graph and its agents are defined in YAML inside each domain pack, and a `GraphCompiler` turns them into the LangGraph4j wiring shown above at startup. Java provides a fixed set of node and agent types; YAML only combines and configures them, so a new domain is mostly configuration.

**Pack layout**

```text
vinnies-pack/src/main/resources/packs/vinnies/
├── ontology.yaml           # concepts, relations, vocabularies (extends rootstock-core)
├── graph.yaml              # nodes, edges, routes, interrupts, runtime settings
├── agents/
│   ├── case-extractor.yaml
│   ├── context-enricher.yaml
│   ├── consistency-judge.yaml
│   ├── action-drafter.yaml
│   └── chase-writer.yaml
├── rules.yaml              # deterministic rule config (limits, windows)
├── tools.yaml              # MCP connection, ontology-to-tool mappings, commit actions
└── generated/              # case-record.json schema + prompt glossary, built from the ontology
```

**Graph definition**

```yaml
apiVersion: rootstock/v1
kind: Graph
metadata:
  name: case-intake
  version: 1.0.0
  pack: vinnies-intake
state:
  channels:
    record:  { reducer: replace }
    context: { reducer: merge }
    issues:  { reducer: append }
    actions: { reducer: append }
    audit:   { reducer: append }
nodes:
  - { id: ingest,   type: ingest }
  - { id: extract,  agent: case-extractor }
  - { id: enrich,   agent: context-enricher }
  - id: validate
    type: rules
    config: { rules: rules.yaml, judge: consistency-judge }
  - id: clarify
    type: clarify
    config: { questionWriter: clarifier, maxRounds: 2 }
  - { id: draft,    agent: action-drafter }
  - { id: chase,    agent: chase-writer }
  - id: review
    type: human-review
    config: { approverRoles: [coordinator] }
  - id: commit
    type: tool-executor
    config: { actions: tools.yaml#commit, allowWrites: true }
  - { id: await_input, type: await-input }
edges:
  - { from: START,   to: ingest }
  - { from: ingest,  to: extract }
  - { from: extract, to: enrich }
  - { from: enrich,  to: validate }
  - from: validate
    route:
      - { when: { issues.anySeverity: BLOCKING, issues.answerableBy: SUBMITTER }, to: clarify }
      - { when: { issues.anySeverity: BLOCKING }, to: chase }
      - { default: draft }
  - { from: clarify, to: extract }
  - { from: draft, to: review }
  - { from: chase, to: review }
  - from: review
    route:
      - { when: { review.decision: APPROVED }, to: commit }
      - { when: { review.decision: EDITED },   to: validate }
      - { default: END }
  - from: commit
    route:
      - { when: { actions.includesType: CHASE_MESSAGE }, to: await_input }
      - { default: END }
  - { from: await_input, to: extract }
runtime:
  checkpointer: postgres
  interruptAfter:  [clarify]
  interruptBefore: [review, await_input]
  maxSteps: 40
```

**Agent definitions**

```yaml
apiVersion: rootstock/v1
kind: Agent
metadata:
  name: case-extractor
spec:
  type: structured-extraction
  model: extraction                 # model profile, mapped to a Bedrock model in application.yml
  prompt: { name: vinnies/extract-case, label: production }   # Langfuse
  input:  { notes: $.rawInput }
  output: { projection: case-extraction, writeTo: record }
  limits: { timeoutSeconds: 30, retries: 2 }
---
apiVersion: rootstock/v1
kind: Agent
metadata:
  name: context-enricher
spec:
  type: tool-calling                # bounded ReAct loop
  model: fast
  prompt: { name: vinnies/enrich, label: production }
  input:  { record: $.record }
  tools:
    connection: vinnies             # MCP connection name
    allow: [find_household, get_assistance_history, get_assistance_guidelines, search_local_services]
  output: { writeTo: context }
  limits: { maxIterations: 4, timeoutSeconds: 45 }
```

**Building blocks provided in Java**

| Kind | Type | What it does |
| --- | --- | --- |
| Agent | `structured-extraction` | One LLM call with structured output against a JSON schema |
| Agent | `tool-calling` | Bounded tool loop over an MCP allowlist; read tools only |
| Agent | `judge` | LLM check that returns issues with severity |
| Agent | `drafter` | Generates text or proposed actions from state (also used to write clarify questions) |
| Node | `ingest` | Normalises input, assigns case and trace ids |
| Node | `rules` | Runs the validation layers: schema, ontology constraints, rules, then the optional judge agent |
| Node | `clarify` | Generates questions from submitter-answerable issues, pauses for answers, enforces max rounds |
| Node | `human-review` | Interrupt point; resumes on a recorded decision |
| Node | `tool-executor` | Runs approved write actions with idempotency keys; the only type allowed `allowWrites` |
| Node | `await-input` | Parks a case after a chase message; resumes when new information arrives |

**Compiler**

```java
public interface NodeFactory {
    String type();                                           // "structured-extraction", "rules", ...
    NodeAction<CaseState> create(NodeSpec spec, PackContext pack);
}

@Component
public class GraphCompiler {
    public CompiledGraph<CaseState> compile(GraphDefinition def, PackContext pack) throws GraphStateException {
        var graph = new StateGraph<>(StateSchemas.from(def.state()), CaseState::new);
        for (var node : def.nodes()) {
            var factory = registry.factoryFor(node);           // agent ref -> its agent type, else node type
            graph.addNode(node.id(), node_async(factory.create(node, pack)));
        }
        for (var edge : def.edges()) {
            if (edge.isRouted()) {
                graph.addConditionalEdges(edge.from(),
                    edge_async(routes.compile(edge.route())), edge.targetMap());
            } else {
                graph.addEdge(ids.of(edge.from()), ids.of(edge.to()));   // START/END mapped to constants
            }
        }
        return graph.compile(CompileConfig.builder()
            .checkpointSaver(savers.get(def.runtime().checkpointer()))
            .interruptBefore(def.runtime().interruptBefore().toArray(String[]::new))
            .build());
    }
}
```

**Validation at startup** (fail fast, before any case runs): YAML checked against a JSON Schema; every node type and agent exists; every edge target exists and every node is reachable from START; every tool in an allowlist exists on the MCP connection; write tools appear only in `tool-executor` nodes with `allowWrites: true`; at least one `human-review` interrupt precedes any write node.

**Routing conditions** use a small declarative set (`equals`, `exists`, `anySeverity`, `countAbove`) on state fields. Anything more complex is a named Java router referenced as `router: <beanName>`. No scripting or expression language in YAML, so definitions cannot run arbitrary code.

**Versioning:** YAML lives in git and changes through pull requests, not at runtime. Each run records the graph and agent versions in Postgres and on its Langfuse trace, and a paused case resumes on the version it started with.

**What stays in code:** node and agent types, rule implementations, complex routers and connector logic. If a new domain needs a new building block, add a reusable type to `rootstock-core` rather than a one-off.

### Supporting components

| Component | Responsibility |
| --- | --- |
| `ToolGateway` | Wraps MCP clients, maps logical tool names to MCP tools, enforces per-node allowlists (read tools in enrich, write tools only in commit) |
| `LlmService` | Spring AI `ChatClient` wrapper: structured output, retries, model routing by task (extraction vs drafting) |
| `PromptRegistry` | Fetches versioned prompts from Langfuse with a local cache and fallback |
| `RuleEngine` | Runs the pack's deterministic rules and merges results with LLM-judge findings |
| `ReviewService` | Creates review tasks, records decisions and diffs, resumes the graph |
| `AuditLog` | Append-only record of inputs, tool calls, decisions and approvals per case |

## Ontology layer

Each domain pack defines its concepts in `ontology.yaml`, extending a small core ontology that lives in `rootstock-core`. Schemas, prompt glossaries, rules, agent outputs and tool mappings are all generated from or checked against it, so a concept is defined once and means the same thing everywhere.

### Core ontology (shared by every domain)

| Concept | Meaning | Vinnies | Insurance broker | Customs broker |
| --- | --- | --- | --- | --- |
| Party | A person or organisation the work is about | Household, Person | Client, Insurer | Importer, Supplier |
| Document | Source material the agent reads | Visit note | Quote, policy schedule | Commercial invoice |
| Case | The unit of work that moves through the graph | Assistance case | Renewal | Import entry |
| Request | Something the party needs | Need | Cover requirement | Goods line to clear |
| Action | A proposed change in the client system | Assistance, Referral, Follow-up | Submission, Report | Declaration draft |
| Issue | A problem found by rules or a judge | Over limit, missing consent | Endorsement missing | Value mismatch |
| Approval | A human decision that authorises Actions | Coordinator approval | Broker sign-off | Licensed broker sign-off |

Generic nodes work on core concepts only: validate raises Issues, draft proposes Actions, review records Approvals, and commit executes approved Actions. This is what keeps the framework domain-agnostic.

&#91;embedded content: Vinnies ontology · 8 concepts, their relations and the core concept each extends\]

### Vinnies ontology

```yaml
apiVersion: rootstock/v1
kind: Ontology
metadata: { name: vinnies, version: 1.0.0, extends: rootstock-core }
entities:
  Case:
    extends: core.Case
    relations:
      concerns:     { to: Household, required: true }
      derivedFrom:  { to: VisitNote }
      identifies:   { to: Need, many: true, min: 1 }
    attributes:
      urgency:      { vocab: Urgency, required: true }
      riskFlags:    { vocab: RiskFlag, many: true }
  Household:
    extends: core.Party
    attributes:
      suburb:       { type: string, required: true, pii: false }
      contact:      { type: string, pii: true }
      consentGiven: { type: boolean }
    relations:
      members:      { to: Person, many: true }
  Need:
    extends: core.Request
    attributes:
      category:     { vocab: NeedCategory, required: true }
  Assistance:
    extends: core.Action
    attributes:
      category:     { vocab: NeedCategory }
      amountAud:    { type: decimal, min: 0 }
    relations:
      meets:        { to: Need }
  Referral:
    extends: core.Action
    relations:
      forNeed:      { to: Need }
      refersTo:     { to: Service }
vocabularies:
  NeedCategory:
    FOOD:        { definition: Food parcels or supermarket vouchers, synonyms: [groceries, food hamper] }
    ENERGY_BILL: { definition: Help with gas or electricity bills, including disconnection notices, synonyms: [power bill, electricity cut off] }
    RENT:        { definition: Help with rent arrears or bond, synonyms: [behind on rent, eviction notice] }
  Urgency:
    HIGH: { definition: Risk to safety or basic needs within 48 hours }
constraints:
  - id: urgent-needs-follow-up
    when: { Case.riskFlags: notEmpty }
    require: { Case.urgency: HIGH }
```

The `pii` flag on attributes drives masking in Langfuse traces and logs, so privacy handling also comes from the ontology.

### How the rest of the framework uses it

| Consumer | Use of the ontology |
| --- | --- |
| Extraction agent | JSON schema for structured output is generated from `Case` and its relations; agent YAML declares `output: { projection: case-extraction }` instead of a hand-written schema |
| Prompts | A glossary of vocabularies with definitions and synonyms is rendered into prompts, so messy wording maps to the right category |
| Rules | Rules reference ontology paths such as `Assistance.amountAud`; ontology constraints become rules automatically |
| MCP tools | `tools.yaml` maps ontology concepts to each tool's parameters; a real client system changes only the mapping |
| Review UI | Forms, labels and help text render from entity attributes and definitions |
| Observability | Traces are tagged with entity types; `pii: true` attributes are masked |
| Startup validator | Rejects agents, rules, routes or mappings that reference unknown concepts, attributes or vocabulary values |

**In Java:** an `OntologyLoader` builds an `OntologyModel`; generators produce the JSON schema and prompt glossary at startup, and an optional Maven plugin generates Java records for type-safe code. Keep it lightweight YAML; OWL, RDF or a graph database can come later if a client needs reasoning across relationships.

### Projections

The ontology is the source of truth; a schema is a **projection** of it, rooted at one entity. Starting from the root, the generator turns attributes into properties, vocabularies into enums (with their definitions as field descriptions), and `required`, `min`, `max` and `many` into schema rules. Each relation is either **embedded** (extracted from the input, such as Household and Needs) or **referenced** by id (already in the client system, such as a Service or member).

```yaml
projections:
  case-extraction:
    root: Case
    embed: [Household, Need, Assistance]
    reference: [Service, Member]
  chase-context:          # smaller view for the chase writer
    root: Case
    include: [urgency, needs.category]
    reference: [Household]
```

For example, `Case.identifies: { to: Need, many: true, min: 1 }` and `urgency: { vocab: Urgency, required: true }` generate:

```json
{
  "title": "Case",
  "type": "object",
  "required": ["household", "needs", "urgency"],
  "properties": {
    "household": { "$ref": "#/$defs/Household" },
    "needs": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/Need" } },
    "urgency": {
      "enum": ["LOW", "MEDIUM", "HIGH"],
      "description": "HIGH: risk to safety or basic needs within 48 hours"
    }
  }
}
```

Agents name the projection they work with (`output: { projection: case-extraction }`), so each agent sees only the fields it needs, which also limits the personal data passed to the model.

**Build effort:** about 2 extra days, added to Phase 2 alongside extraction.

## Vinnies domain pack (vinnies-pack)

`vinnies-pack` is the only module that knows about Vinnies. It holds the ontology, graph, agents, rules, prompt names and tool mappings. Schemas and Java records are generated from the ontology, never written by hand. All data is fictional.

### Generated case record

The extraction schema and the `CaseRecord` type are produced from the `case-extraction` projection of the Vinnies ontology (see Ontology layer). The optional Maven plugin generates the Java record so code can stay type-safe; regenerate it whenever the ontology changes.

```java
// GENERATED from ontology.yaml, projection "case-extraction". Do not edit.
public record CaseRecord(
    HouseholdRecord household,            // embedded: Case.concerns -> Household
    List<NeedRecord> needs,               // embedded: Case.identifies -> Need (min 1)
    List<AssistanceRecord> assistance,    // embedded: Assistance meets Need
    Urgency urgency,                      // vocab Urgency: LOW, MEDIUM, HIGH
    List<RiskFlag> riskFlags,             // vocab RiskFlag
    String visitSummary,
    LocalDate visitDate,
    String visitingMemberRef              // referenced by id, not embedded
) {}
```

### Rules

| Rule id | Check | Severity |
| --- | --- | --- |
| `R01-mandatory` | Household, visit date, at least one need and visiting member present | Blocking |
| `R02-amount-limit` | Assistance per type within the configured limit per visit | Warning |
| `R03-frequency` | Same assistance type for the household within the configured window (from history tool) | Warning |
| `R04-urgent-risk` | Risk flags present require urgency HIGH and a follow-up within 2 days | Blocking |
| `R05-new-household` | New household needs contact details and consent recorded | Blocking |
| `J01-consistency` (LLM judge) | Summary, needs and assistance are consistent with the original notes | Warning |

Rule **logic** lives in the pack. Threshold **values** (limits, frequency windows) come from the client system through `get_assistance_guidelines` during enrich, so client staff can change them without a pack release; `rules.yaml` keeps only fallback defaults.

### Prompts (managed in Langfuse)

- `vinnies/extract-case` extracts the record from visit notes; uses the schema above as structured output.
- `vinnies/judge-consistency` compares the record with the notes and returns issues.
- `vinnies/draft-referrals` ranks services from the directory tool against the needs.
- `vinnies/draft-chase` writes a short, respectful request for missing details.

### Tool bindings

| Graph step | MCP tool | Purpose |
| --- | --- | --- |
| enrich | `find_household` | Match the household by name and suburb |
| enrich | `get_assistance_history` | Past assistance for frequency checks |
| enrich | `get_assistance_guidelines` | Current guideline text for the judge and reviewers |
| enrich | `search_local_services` | Candidate referrals by need and suburb |
| commit | `create_case` | Write the approved case record |
| commit | `create_referral` | Record each approved referral |
| commit | `schedule_follow_up` | Book a follow-up visit or call |
| chase | `send_message` | Send the approved missing-info request (mock outbox) |

## Dummy Vinnies application (vinnies-mcp-server)

`vinnies-mcp-server` plays the role of the client's existing system. It is a small Spring Boot app with its own Postgres schema and fictional seed data, and it exposes its functions as MCP tools. In a real engagement this is the connector you would build on top of the client's actual system.

**Seed data:** about 50 households, 200 past assistance records, and 40 local services across Sydney suburbs, all fictional.

### Tool catalogue

| Tool | Type | Input | Returns | Required scope |
| --- | --- | --- | --- | --- |
| `find_household` | Read | name, suburb, phone (optional) | Candidate households with match score | `vinnies/read` |
| `get_assistance_history` | Read | householdRef, sinceDays | Past assistance: date, type, amount | `vinnies/read` |
| `get_assistance_guidelines` | Read | assistanceType | Guideline text, limit, window | `vinnies/read` |
| `search_local_services` | Read | needType, suburb | Services with address, hours, eligibility | `vinnies/read` |
| `create_case` | Write | CaseRecord, idempotencyKey, approvalId | caseId | `vinnies/write` |
| `create_referral` | Write | caseId, serviceId, reason, idempotencyKey | referralId | `vinnies/write` |
| `schedule_follow_up` | Write | caseId, dueDate, channel, idempotencyKey | followUpId | `vinnies/write` |
| `send_message` | Write | recipient, channel, body, idempotencyKey | messageId (mock outbox, never really sent) | `vinnies/write` |

The assistance guidelines are also published as MCP **resources**, so reviewers and the judge prompt can read the same source text.

### Example tool

```java
@Service
public class AssistanceTools {

    @Tool(description = "Past assistance for a household within the last N days")
    public List<AssistanceDto> get_assistance_history(
            @ToolParam(description = "Household reference") String householdRef,
            @ToolParam(description = "Look-back window in days") int sinceDays) {
        return repo.findRecent(householdRef, sinceDays);
    }

    @Tool(description = "Create an approved case record. Requires approvalId.")
    public CaseCreated create_case(CaseRecord record, String idempotencyKey, String approvalId) {
        approvals.assertValid(approvalId);          // write only after human approval
        return cases.createIdempotent(record, idempotencyKey);
    }
}

@Bean
ToolCallbackProvider vinniesTools(AssistanceTools a, HouseholdTools h, ServiceTools s) {
    return MethodToolCallbackProvider.builder().toolObjects(a, h, s).build();
}
```

Use the Spring AI MCP server starter (`spring-ai-starter-mcp-server-webmvc`) over HTTP, and protect it as an OAuth2 resource server that validates Cognito JWTs and checks scopes per tool.

**Design rules for the tools:** small and single-purpose, typed inputs, clear descriptions (they are the LLM's documentation), every write idempotent and tied to an approval id, and no tool that returns more personal data than the step needs.

## Runtime integration (rootstock-runtime)

`rootstock-runtime` is your existing Spring AI project, extended. It already connects Bedrock, Cognito and Postgres; the new pieces are the LangGraph4j graph, the MCP client and Langfuse tracing.

### Spring AI and Bedrock

- Use the Bedrock Converse starter in `ap-southeast-2` (Sydney) so data stays in Australia.
- Route models by task through `LlmService`: a stronger Claude model for extraction and drafting, a faster, cheaper model for classification and the consistency judge.
- Use structured output (`ChatClient...entity(CaseRecord.class)`) for extraction, with a schema-validation retry when the output does not parse.
- Set temperature low for extraction and checks; slightly higher only for drafted messages.

### MCP client

- Use the Spring AI MCP client starter with one named connection per client system (`vinnies` now; `broker-x` later).
- `ToolGateway` resolves logical tool names from the domain pack to MCP tools on that connection and blocks any tool not on the current node's allowlist.
- Tool calls carry a bearer token from Cognito (below), plus `caseId` and `actingUser` for the client app's own audit.

### Cognito

| Concern | Approach |
| --- | --- |
| Users | One user pool with groups `volunteer`, `coordinator`, `admin` |
| Agent API | `rootstock-runtime` is an OAuth2 resource server; volunteers submit notes, only coordinators decide reviews |
| Agent to MCP server | Machine app client with client-credentials grant and custom scopes `vinnies/read`, `vinnies/write` (resource server in Cognito) |
| Least privilege | The write-scoped token is requested only inside the commit node, after an approval exists |

### Postgres

| Schema or store | Contents |
| --- | --- |
| `rootstock.checkpoints` | LangGraph4j checkpoint saver tables (paused and resumable runs) |
| `rootstock.run` | One row per graph run: case, pack, status, Langfuse trace id |
| `rootstock.review_task` | Pending reviews, reviewer, decision, edited diff |
| `rootstock.audit_event` | Append-only log: inputs, tool calls, rule results, approvals |
| `rootstock.knowledge` (pgvector) | Embedded procedure documents for UC5, Titan embeddings via Bedrock |
| Separate database `vinnies_mock` | Owned by the mock app only, to mimic a real client system |

### API for the demo UI

| Endpoint | Purpose |
| --- | --- |
| `POST /cases` | Submit visit notes; starts a run, returns caseId and runId |
| `GET /cases/{id}/events` (SSE) | Live node-by-node progress for the demo screen |
| `GET /reviews?status=PENDING` | Coordinator's review queue |
| `POST /reviews/{id}/decision` | Approve, edit or reject; resumes the graph |
| `POST /cases/{id}/input` | New information after a chase; resumes the parked case |

## LLMOps with Langfuse

Langfuse covers four jobs: tracing every run, managing prompts, scoring quality, and running evaluations before changes ship. It is also a strong demo asset, because clients can see exactly what the agent did and why.

### Tracing through OpenTelemetry

Spring AI already emits Micrometer observations for chat, embedding and tool calls. Bridge them to OpenTelemetry and export to Langfuse's OTLP endpoint, so no custom tracing client is needed.

```properties
# dependencies: micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=https://<langfuse-host>/api/public/otel/v1/traces
management.otlp.tracing.headers.Authorization=Basic <base64(publicKey:secretKey)>
# include prompt and completion text in spans (demo data only; see privacy)
spring.ai.chat.observations.log-prompt=true
spring.ai.chat.observations.log-completion=true
```

Property names vary across Spring AI and Spring Boot versions; confirm against the versions you pin.

**Trace structure:** one trace per graph run, with the case id as the Langfuse session, so a case that pauses for review and resumes later shows as one story.

- Wrap each LangGraph4j node in a Micrometer `Observation` named `node.<name>` so nodes appear as spans.
- LLM calls and MCP tool calls appear as child spans of their node.
- Add attributes for case id, domain pack, acting user and prompt name and version.

### Prompt management

- Store the four Vinnies prompts in Langfuse with labels `production` and `staging`.
- `PromptRegistry` fetches by name and label, caches for a few minutes, and falls back to a bundled copy if Langfuse is unreachable.
- Record the prompt version on each generation, so any quality change can be traced to a prompt change.

### Scores

| Score | Source | Why it matters |
| --- | --- | --- |
| `review_outcome` | Coordinator decision: approved, edited, rejected | The single best real-world quality signal |
| `fields_edited` | Count of fields the reviewer changed | Shows where extraction is weak |
| `rule_issues` | Count by severity from validate | Tracks data quality coming in |
| `consistency` | LLM-as-judge evaluator in Langfuse | Catches summaries that drift from the notes |
| Cost and latency | Captured automatically per trace | Needed for pricing a client offer |

### Datasets and evals

- Build a dataset of 30 to 50 fictional visit notes with expected case records, including messy and edge cases (missing details, urgent risk, repeat requests).
- Run the dataset as an experiment on every prompt or model change and compare field-level accuracy, rule results, cost and latency before promoting a prompt to `production`.
- Turn real reviewer edits into new dataset items over time (with client permission and de-identification).

### Hosting

For the demo with fictional data, Langfuse Cloud is the quickest start. For real client data, self-host Langfuse on AWS in the Sydney region so traces stay in Australia. Plan for its supporting services (Postgres, ClickHouse, Redis and S3-compatible storage), or turn off prompt and completion logging and log metadata only.

## Security, privacy and human-in-the-loop

For a charity serving vulnerable people, these controls are the main selling point, not an afterthought. Show them in the demo.

| Control | How it is enforced |
| --- | --- |
| No write without approval | Graph interrupt before review; write tools require a valid `approvalId`; write scope requested only in commit |
| Least-privilege tools | Per-node tool allowlist in `ToolGateway`; read and write scopes split in Cognito |
| Idempotent writes | Every write carries an idempotency key derived from case id and action, so retries never duplicate records |
| Data residency | Bedrock, Postgres and (for real data) Langfuse in `ap-southeast-2` |
| Data minimisation | Tools return only the fields a step needs; no free-form database access for the LLM |
| PII in observability | Prompt and completion logging only with fictional data; masking or metadata-only tracing for real clients |
| Prompt injection | Notes, documents and tool results are treated as data; the graph, not the LLM, decides which tools run; untrusted text never changes the allowlist |
| Full audit trail | `rootstock.audit_event` plus the Langfuse trace for every case: inputs, rule results, tool calls, reviewer and decision |
| Role separation | Volunteers submit, coordinators approve, admins configure rules and prompts |
| Fail safe | Any LLM, schema or tool failure parks the case for a human with the error attached; nothing is written automatically |

**Before using real client data:** a privacy impact assessment with the client, a data processing agreement, retention and deletion rules for checkpoints and traces, and confirmation of which Bedrock models are approved for their data.

## Project structure and build plan

Two separate projects. **Rootstock** is one Spring Boot project containing only domain-agnostic code, with boundaries kept by packages and ArchUnit tests. **Everything Vinnies** (the domain pack and the dummy MCP server) lives in its own project outside Rootstock, exactly as a future client's pack and connector would. Rootstock can be split into modules later, when it is packaged for clients.

```text
rootstock/                              # Rootstock project: domain-agnostic, no Vinnies code
├── src/main/java/.../rootstock/
│   ├── core/                           # graph template, SPI, generic nodes, rules, ontology, ToolGateway
│   ├── autoconfig/                     # Bedrock LlmService, MCP client, checkpointer, Langfuse/OTel, PromptRegistry
│   └── runtime/                        # REST/SSE API, Cognito security, review queue, pack loader
├── src/main/resources/ontology/
│   └── rootstock-core.yaml             # core ontology
├── src/test/resources/packs/sample/    # tiny generic test pack, used only by Rootstock tests
├── evals/                              # generic experiment runner
├── demo-ui/                            # ontology-driven submit, clarify and review screens
└── infra/                              # AWS dev environment as code (CDK or Terraform); single configuration, no profiles

vinnies/                                # Vinnies project: everything domain-specific
├── vinnies-pack/
│   ├── packs/vinnies/                  # ontology, graph, agents, rules, tools (YAML)
│   └── src/main/java/...               # optional extensions jar (custom rules or routers), depends on Rootstock
├── vinnies-mcp-server/                 # dummy Vinnies app exposing MCP tools, own DB + seed data
└── evals/                              # Vinnies datasets for Langfuse
```

**How Rootstock loads an external pack:** pack YAML is read from configured locations (`rootstock.packs.paths`, a local folder for now, S3 or a pack registry later). If a pack ships Java extensions, its jar is added to the runtime classpath at start-up (for example with Spring Boot's `loader.path`), and extensions are found through the `DomainPack` SPI. Rootstock is installed as a Maven artifact (`mvn install` locally, a private repository later) so the extensions jar can depend on it.

**Dependency rules (enforced by ArchUnit in Rootstock):** `core` depends on nothing in `autoconfig` or `runtime`, and nothing in Rootstock references Vinnies or any other domain. The Vinnies project depends on Rootstock, never the reverse. A new client means a new pack project and a new connector, with no change to Rootstock.

### Work sequence

Granular tasks grouped into **iterations**. Each iteration is a vertical slice that ends with something new you can show, so progress is visible and every session is testable. Finish an iteration before starting the next.

**Starting point:** a Spring AI project with LangGraph4j (`rootstock`), already connected to Langfuse, Cognito, RDS (Postgres) and Bedrock, with a demo front end. Rootstock stays one domain-agnostic project, organised by packages; all Vinnies work (pack and MCP server) goes in a separate `vinnies` project.

**Working agreements**

- **One configuration, AWS only.** No profiles and no local substitutes. Rootstock and the Vinnies apps run on your laptop (or in AWS later) against real AWS services: RDS Postgres, Cognito and Bedrock, plus Langfuse. Settings come from environment variables loaded from a local `.env` file that is gitignored; a committed `.env.example` lists the variable names only. Moving secrets to Secrets Manager and SSM is an enterprise production task (see the last section).
- **Data isolation in the shared AWS environment.** Schema `rootstock` for the platform, database `vinnies_mock` for the dummy client app, and a throwaway schema per integration-test run that the test creates and drops.
- **Test layers.** Unit tests (LLM mocked) run in seconds. Integration tests call AWS. Tests that call Bedrock are tagged `live` and run on demand, which keeps cost under control.
- **Every iteration ends the same way:** automated tests green, the demo script runs end to end, a Langfuse trace exists for the demo run, and the commit is tagged `demo-<n>`.

#### Iteration 0: Foundation on one AWS configuration

**Demo:** a Platform status screen with green checks for Bedrock, RDS, Cognito login and Langfuse (with a link to the latest trace); existing chat and RAG still work.

| ID | Task | Done when |
| --- | --- | --- |
| 0.1 | Audit the repo: modules, packages, versions of Spring Boot, Spring AI, LangGraph4j and the MCP SDK; pin versions | Versions recorded in `CLAUDE.md`, build green |
| 0.2 | Add `CLAUDE.md` (package rules, one-configuration rule, test conventions) and export this design to `docs/design.md` | Both files committed |
| 0.3 | Collapse to a single configuration: remove other profiles and read all settings from environment variables loaded from a gitignored `.env` file; commit `.env.example` with names only | One `application.yml`, no profile flags; chat and RAG still work |
| 0.4 | Organise packages `core`, `autoconfig`, `runtime`; add ArchUnit rules (`core` independent of the others; nothing references Vinnies) | Tests pass, and fail when a rule is broken deliberately |
| 0.5 | Status endpoint and demo UI screen checking Bedrock, RDS, Cognito and Langfuse | All four green; trace link opens in Langfuse |
| 0.6 | AWS test support: throwaway schema helper for integration tests, `live` tag for Bedrock tests | One integration test creates, uses and drops a schema in RDS |

#### Iteration 1: Vinnies project and first tool

**Demo:** in MCP Inspector, call `find_household` and get ranked matches from fictional data stored in RDS.

| ID | Task | Done when |
| --- | --- | --- |
| 1.1 | Create the `vinnies` project with a `vinnies-mcp-server` skeleton (Spring AI MCP server starter) and a ping tool | Inspector connects and calls ping |
| 1.2 | Database `vinnies_mock` on RDS, Flyway migrations, entities | Migrations apply to RDS |
| 1.3 | Fictional seed data: about 50 households, 200 assistance records, 40 services | Seed and reset commands are repeatable |
| 1.4 | Tool `find_household` (name, suburb, optional phone) | Ranked matches with scores in Inspector |
| 1.5 | Integration tests for tools using a throwaway schema | Tests pass |

#### Iteration 2: Complete and secure the read tools

**Demo:** in Inspector a call without a token is rejected; with a `vinnies/read` token all read tools and the guidelines resource work.

| ID | Task | Done when |
| --- | --- | --- |
| 2.1 | Tool `get_assistance_history` | Returns correct history for seeded households |
| 2.2 | Tool `get_assistance_guidelines` and the same content as an MCP resource | Tool and resource both work |
| 2.3 | Tool `search_local_services` (need type, suburb) | Returns services with address, hours and eligibility |
| 2.4 | Cognito resource server, scopes `vinnies/read` and `vinnies/write`, machine-to-machine app client | Token issued with the right scopes |
| 2.5 | JWT validation and per-tool scope checks | No token or wrong scope is rejected |

#### Iteration 3: Rootstock calls Vinnies tools

**Demo:** in a Tool explorer screen, pick a tool and run it through Rootstock to see the result and its Langfuse span; try a tool outside the allowlist and see it rejected.

| ID | Task | Done when |
| --- | --- | --- |
| 3.1 | MCP client with connection `vinnies`; client-credentials token from Cognito with caching | Runtime lists the server's tools at start-up |
| 3.2 | `ToolGateway`: logical tool names, per-node allowlists, error mapping | Unit tests cover allowed, blocked and failing calls |
| 3.3 | Tool-call spans in Langfuse with case id attributes | Test call visible in Langfuse |
| 3.4 | Tool explorer endpoint and screen | Allowed call shows a result; blocked call shows the reason |

#### Iteration 4: An ontology you can see

**Demo:** an Ontology explorer screen lists concepts, relations and vocabularies and shows the generated extraction schema; a deliberately broken ontology shows a clear error.

| ID | Task | Done when |
| --- | --- | --- |
| 4.1 | Ontology model and YAML loader, plus the `rootstock-core` core ontology | Core ontology loads in a unit test |
| 4.2 | External pack loader reading `rootstock.packs.paths` | Rootstock lists the packs it found |
| 4.3 | Vinnies `ontology.yaml` in the `vinnies` project | Loads and extends the core ontology |
| 4.4 | Ontology validator: references, vocabularies, constraints | Broken ontologies fail with clear messages |
| 4.5 | Projections and JSON Schema generator | Snapshot test of the `case-extraction` schema |
| 4.6 | Prompt glossary renderer | Snapshot test of the glossary |
| 4.7 | Explorer endpoints and screen | Concepts, vocabularies and schema visible in the UI |

#### Iteration 5: A graph that runs (stub nodes)

**Demo:** submit notes in the UI and watch each node light up live; one Langfuse trace shows a span per node.

| ID | Task | Done when |
| --- | --- | --- |
| 5.1 | YAML models and JSON Schemas for `Graph` and `Agent`; loader | Vinnies graph and agents load |
| 5.2 | State channels built from YAML (replace, merge, append reducers) | Reducer unit tests pass |
| 5.3 | Node and agent factory registry with stub implementations | Every type in the Vinnies YAML resolves |
| 5.4 | Route condition evaluator and named router beans | Routing unit tests pass |
| 5.5 | `GraphCompiler` to LangGraph4j (in-memory checkpointer for now) | Stub graph runs end to end |
| 5.6 | Startup validator including safety invariants | Invalid graphs fail start-up with clear errors |
| 5.7 | `POST /cases`, SSE progress events, node spans | Live node progress in the UI; one trace in Langfuse |

#### Iteration 6: Notes become a structured record (first real AI)

**Demo:** paste messy visit notes and see an extracted record (needs, urgency, risk flags) using the ontology's own categories, beside the raw notes.

| ID | Task | Done when |
| --- | --- | --- |
| 6.1 | `LlmService` with model profiles; `PromptRegistry` from Langfuse with cache and fallback (reuse your existing integration) | Prompt fetched by name and label |
| 6.2 | `ingest` node | Notes normalised, case and trace ids assigned |
| 6.3 | `structured-extraction` agent using the projection schema, with validation and retry | Invalid output is retried, then parked |
| 6.4 | Vinnies extraction prompt in Langfuse and 5 fictional sample notes | Records correct on all samples (`live` tests) |
| 6.5 | UI shows the extracted record next to the raw notes | Demo works end to end |

#### Iteration 7: Enrich with live client data

**Demo:** the same notes now also show the matched household, past assistance, and the applicable guideline, all fetched from the Vinnies app.

| ID | Task | Done when |
| --- | --- | --- |
| 7.1 | `tool-calling` agent type: bounded loop, allowlist enforced | Unit tests, including loop limit and blocked tool |
| 7.2 | Vinnies enrich prompt and context plan | Context filled from the read tools |
| 7.3 | UI context panel | Household, history and guideline visible |

#### Iteration 8: Validation that catches problems

**Demo:** notes with problems show issues: missing consent, amount over limit, repeat request inside the window, urgent risk without HIGH urgency.

| ID | Task | Done when |
| --- | --- | --- |
| 8.1 | Structural and semantic validation layers (schema and ontology constraints) | Layer tests pass |
| 8.2 | Rule SPI and engine; Vinnies rules R01 to R05 with thresholds from the guidelines tool and YAML defaults | Rule tests pass |
| 8.3 | Issue model with severity and `answerableBy` | Model tests pass |
| 8.4 | `judge` agent type and consistency prompt | Invented details flagged on test notes (`live`) |
| 8.5 | UI issues list with severity | Each demo problem appears with the right severity |

#### Iteration 9: Draft and human review (persistent)

**Demo:** a coordinator signs in, sees a pending review with the draft and issues, and approves it; restart the app mid-review and the case is still waiting.

| ID | Task | Done when |
| --- | --- | --- |
| 9.1 | Postgres checkpoint saver on RDS, `rootstock` schema via Flyway | A run survives an app restart |
| 9.2 | `run` and `review_task` tables; `human-review` node; interrupt and resume service | Run pauses and resumes correctly |
| 9.3 | `drafter` agent type and draft node (record, referral suggestions from `search_local_services`, follow-up) | Draft appears in the review task |
| 9.4 | Review API with Cognito roles | Volunteers cannot decide reviews |
| 9.5 | Review screen rendered from the ontology: approve, edit or reject; edits go back through validation | Coordinator completes a review in the UI |

#### Iteration 10: Approved cases are written

**Demo:** approve a case and see it, its referrals and its follow-up appear in the Vinnies app; approve again and nothing is duplicated; a write without approval is refused.

| ID | Task | Done when |
| --- | --- | --- |
| 10.1 | Write tools `create_case`, `create_referral`, `schedule_follow_up` with approval id and idempotency key | Duplicate calls create one record; no-approval calls rejected |
| 10.2 | Read tool `get_case` so written results can be shown | Returns the stored case |
| 10.3 | `tool-executor` node, `tools.yaml` ontology-to-tool mappings, write-scoped token requested only in commit | Approved case written to the mock app |
| 10.4 | Case screen showing what the Vinnies app now holds | Written records visible in the UI |
| 10.5 | Failure handling: tool errors park the case for a human | Simulated failure parks safely |

#### Iteration 11: Ask the volunteer (clarify)

**Demo:** notes missing the visit date or consent trigger a question to the volunteer; they answer inline and the case continues.

| ID | Task | Done when |
| --- | --- | --- |
| 11.1 | Routing on `answerableBy` to clarify | Routing tests pass |
| 11.2 | `clarify` node: questions from issues, pause, maximum rounds | Loop stops after the limit |
| 11.3 | Answers endpoint and merge into the input | Re-extraction uses the answers |
| 11.4 | Clarify panel in the UI | Missing detail answered inline |

#### Iteration 12: Chase and wait

**Demo:** a missing household contact detail produces a drafted request; once approved it is sent to a mock outbox and the case parks; a reply resumes it.

| ID | Task | Done when |
| --- | --- | --- |
| 12.1 | `chase-writer` agent and prompt | Draft is short, respectful and specific |
| 12.2 | Write tool `send_message` (mock outbox) and an outbox view | Message appears in the outbox after approval |
| 12.3 | `await-input` node and `POST /cases/{id}/input` | Case parks, then resumes on new input |
| 12.4 | UI: parked cases list and an add-reply action | Full chase loop demonstrated |

#### Iteration 13: Audit and trust

**Demo:** a case timeline shows every step, tool call, rule result and approval, with a link to the Langfuse trace; personal data is masked in traces.

| ID | Task | Done when |
| --- | --- | --- |
| 13.1 | `audit_event` log written by every node and tool call | Complete trail for a test case |
| 13.2 | Timeline endpoint and screen | Timeline readable by a non-technical viewer |
| 13.3 | Ontology `pii` flags drive masking in traces and logs | No personal data in Langfuse for flagged fields |

#### Iteration 14: Evals and quality

**Demo:** a Langfuse experiment compares two prompt versions on the dataset, and reviewer decisions appear as scores on traces.

| ID | Task | Done when |
| --- | --- | --- |
| 14.1 | Langfuse dataset of 30 to 50 fictional notes with expected records | Dataset visible in Langfuse |
| 14.2 | Experiment runner with field-level accuracy evaluator | Report per prompt or model version |
| 14.3 | Scores from review outcomes (approved, edited, rejected, fields edited) | Scores on traces |
| 14.4 | Consistency judge as a Langfuse evaluator | Scores appear automatically |

#### Iteration 15: Live ontology change

**Demo:** add a `PHARMACY` need category with synonyms, reload the pack, and notes mentioning a chemist bill extract correctly; no Java changes.

| ID | Task | Done when |
| --- | --- | --- |
| 15.1 | Pack reload endpoint with validation; in-flight cases keep their starting version | Reload applies; invalid pack is refused |
| 15.2 | Pack and graph versions recorded on runs and traces | Version visible in Langfuse |
| 15.3 | Rehearse the `PHARMACY` scenario | Works from a clean start |

#### Iteration 16: Hosted demo and recording

**Demo:** the full 3-minute story on a hosted URL that anyone you invite can open.

| ID | Task | Done when |
| --- | --- | --- |
| 16.1 | Deploy Rootstock, the Vinnies mock and the demo UI to AWS (for example ECS Fargate or App Runner) over HTTPS, with the same variables as `.env` set on the services | Hosted URL works end to end |
| 16.2 | Demo users in Cognito and a reset command for seeded cases | One command returns the demo to a clean state |
| 16.3 | Rehearse and record the demo | 3-minute video ready |

**Optional:** reuse your existing RAG implementation for procedures Q&A (UC5). Demo: ask a volunteer procedures question and get a cited answer.

## Demo script and reuse path

The 3-minute demo tells one story: a volunteer's rough notes become a checked, approved case in under a minute, with a person in control throughout.

1. **The problem (20 s).** "After every home visit, volunteers spend time writing up notes and filling forms instead of helping people."
2. **Submit notes (20 s).** Paste messy, realistic visit notes: a family with three children, an energy disconnection notice, no food until payday.
3. **Watch the agent work (40 s).** Live progress: extracted record, household matched, history checked, a frequency warning raised, urgent risk flagged, referrals suggested.
4. **Human review (40 s).** The coordinator sees the draft with issues highlighted, edits one field, and approves.
5. **Written back (20 s).** Case, referrals and follow-up appear in the "Vinnies" app via MCP tools.
6. **Trust (40 s).** Open the Langfuse trace: every step, tool call, prompt version, cost per case, and the audit trail.

**Second scenario for live demos:** notes with missing details, where the agent drafts a polite request instead of guessing.

**Ontology moment (extended demo, about 60 s):** open `ontology.yaml` and the core-concept diagram, then add a new need category live, for example `PHARMACY` with synonyms "chemist bill" and "medication costs". Reload the pack and submit notes mentioning a chemist bill: extraction picks the new category, the rules and review form show it, and nothing in Java changed. Close by showing the same core concepts mapped to insurance and customs in the core ontology table.

### Reuse path

| Next domain | New domain pack | New connector | Framework changes |
| --- | --- | --- | --- |
| Insurance broker | Renewal record, policy-check rules, comparison prompts | MCP server over the broker's management system or document store | None expected |
| Customs broker | Entry record, document cross-check rules, classification prompts | MCP server over the broking system or a document inbox | Possibly a document-ingest node for PDFs (reusable) |

This is the core pitch for Sinew Labs: the framework is built once and proven; each new client pays for a domain pack and a connector, which is faster and cheaper than a custom build.

## Pack delivery and MCP resources

A domain pack holds **definitions** (how to process a case), never **data** (facts about households or services). Keeping these apart decides who owns each piece and how it reaches Rootstock.

| Information | Owner | Delivered how | Changes how |
| --- | --- | --- | --- |
| Graph, agents, tool allowlists, review requirements, core ontology | Sinew Labs | Domain pack, versioned in git; optionally served from a pack registry Sinew runs (MCP server, S3 or config service) | Release through pull request |
| Domain ontology, rule logic, tool mappings, prompt names | Sinew Labs, with the client | Domain pack | Release |
| Prompt text | Sinew Labs | Langfuse, by name and label | Promote a version after evals |
| Reference knowledge: guidelines, thresholds, service categories, extra synonyms | Client | MCP resources from the client app, read at startup and refreshed on change notifications | Client edits in their own system |
| Live data: households, history, writes | Client | MCP tools, per case | Every case |

**Why the client's MCP server never supplies the graph:** graph and agent definitions control which tools can write and where a human must approve. Content from a client system is treated as data, not instructions, so a bug or compromise there must not be able to change control flow or permissions.

**Safeguards when Rootstock loads definitions at startup** (from files or a pack registry):

1. Validate every definition against the JSON Schemas and the startup validator.
2. Verify integrity with a signature or checksum, accepting only packs Sinew published.
3. Pin versions, record them on every run, and resume paused cases on their starting version.
4. Cache the last known good version so the runtime starts if the registry is down, and never apply a definition that fails validation.
5. Enforce safety invariants in code: write tools always need an approval, whatever the YAML says.

**Reload:** a pack reload endpoint re-reads and re-validates definitions without a restart (used in the demo's ontology moment). In production, changes still go through a release.

## Product and delivery model

Sinew Rootstock is sold as a platform plus configuration, not a custom build. Each client gets the same core, a domain pack and a connector to their system.

| Part | What it is | Per-client effort |
| --- | --- | --- |
| Rootstock core | Graph engine, building blocks, core ontology, review flow, audit, Langfuse integration, safety controls | None; same version for every client |
| Domain pack | Ontology, graph, agents, rules, tool mappings (YAML) | Mostly configuration; days once an industry pack exists |
| Domain MCP server | Connector to the client's system | The main per-client code |

Target: roughly 80% configuration and 20% code per client, with the code only in the connector.

### What makes the core reusable

1. **Stable, versioned SPI.** `DomainPack`, the YAML formats and the core ontology are a public API with semantic versioning.
2. **Pack starter kit.** Template pack plus a validator CLI that checks a pack before deployment.
3. **Connector starter kit.** Spring AI MCP server template with Cognito auth, idempotency, approval checks and audit built in.
4. **Generic review UI** rendered from the ontology, so no per-client front end.
5. **Eval harness.** Load a client's sample cases into Langfuse and report accuracy before go-live; also strong sales evidence.
6. **Deployment templates** (CDK or Terraform) for the whole stack.

### Delivery

Start with deployment into each client's own AWS account, or a dedicated single-tenant environment run by Sinew. This suits regulated clients and charities handling sensitive data and keeps data residency simple. Multi-tenant SaaS can follow once volume justifies it.

### Commercial model

- **Setup fee** (fixed price): discovery, pack configuration, connector build, eval on the client's sample data.
- **Platform subscription** (monthly): licence, updates and monitoring, tiered by case volume.
- **Optional retainer:** new use cases, prompt tuning, Fractional CTO advice.

Contracts state that Sinew owns the core and reusable industry packs; the client owns their data and client-specific configuration.

### Risks

| Risk | Response |
| --- | --- |
| Connector effort varies with the client's system (API, documents, email, browser) | Scope in discovery and price separately |
| Scope creep into one-off features | Configure it, or build a reusable building block in the core; no client-only code |
| IP and outside-work obligations from current employment | Check contract clauses; build on own time and equipment |

**Proof point to aim for:** after the Vinnies demo, build a small second pack (for example an insurance renewal check) in a few days with no core changes.

## Later: database-driven packs and a pack studio

Not in scope for the first build. Because the compiler, ontology loader and validator work on an in-memory model rather than files, the pack can later move from YAML into a database edited through a UI (a "pack studio") without changing the rest of Rootstock. The JSON Schemas used for validation can also drive the UI forms.

**Requirements when this is built:**

1. **Draft and publish.** Edits go into a draft, are validated and run against the Langfuse eval dataset, then published as an immutable version. Live versions are never edited in place.
2. **Version pinning.** Paused cases resume on the version they started with.
3. **Guarded safety controls.** Client admins can change vocabularies, synonyms, thresholds and prompt selection, but not write-tool allowlists or the human review requirement; the validator enforces this whatever the UI allows.
4. **Audit and roles.** Every change recorded, with approval required to publish.
5. **YAML import and export.** Packs can still live in git, be diffed and reviewed, and move between environments and clients.

**Suggested order:** forms for vocabularies and synonyms, rule thresholds and prompt selection first, with a test panel for sample notes. A visual graph editor is the most expensive part and changes least often, so graph structure stays in YAML (or a read-only visual view) until clients need to edit it.

## Enterprise production readiness (out of scope for the kickstart)

The kickstart proves the architecture with fictional data. Taking Rootstock to an enterprise client in production needs the work below. Each item is scoped per client during discovery and priced into setup, because most of it depends on the client's existing platforms and obligations.

### Platform and integration

| Area | Kickstart | Enterprise production |
| --- | --- | --- |
| Langfuse hosting | Langfuse Cloud, fictional data | Self-hosted in the client's AWS account or on-premises (Postgres, ClickHouse, Redis, S3), with HA, backups, retention rules, SSO for users and PII masking; or Langfuse Cloud with a data processing agreement and approved region |
| Identity | Cognito user pool with local users | Federation with the enterprise IdP (Entra ID, Okta, Ping) over SAML or OIDC, group-to-role mapping, MFA and session policies from the client, user provisioning (SCIM), workload identity for service-to-service calls |
| Central logging | Application logs only | Structured JSON logs with trace id and case id, shipped to Splunk, Datadog, Elastic or the client's SIEM; audit events forwarded as security events; redaction before logs leave the app |
| Monitoring and alerting | Langfuse traces | Metrics to CloudWatch, Prometheus or the client's APM; dashboards and alerts on failure rate, latency, cost per case and review backlog; on-call runbooks |
| Client system integration | Dummy MCP server | Connectors to real systems with retries, timeouts, circuit breakers, contract tests and a sandbox environment; incidents and changes through the client's ITSM (for example ServiceNow) |
| Environments and delivery | Single AWS dev environment | Dev, test, staging and production as infrastructure as code; pack promotion pipeline with eval gates; blue-green deploys and rollback |

### Security and data protection

| Area | Enterprise production |
| --- | --- |
| Network | Private VPC, VPC endpoints for Bedrock and AWS services, no public endpoints for internal components, WAF on public APIs, mTLS to MCP servers, egress control |
| Secrets and keys | Replace local `.env` files with Secrets Manager (with rotation) for secrets and SSM Parameter Store for non-secret settings; KMS customer-managed keys; nothing secret in configuration or in git, enforced with secret scanning |
| Encryption and retention | Encryption at rest and in transit; retention and deletion rules for checkpoints, traces, audit logs and knowledge stores |
| Privacy | Privacy impact assessment, data classification, compliance with the Privacy Act and Australian Privacy Principles, support for access and deletion requests |
| Security assurance | Threat model including prompt injection, penetration test, SAST, dependency scanning and SBOM, completion of client security questionnaires |
| Regulatory alignment | Map controls to the client's obligations, for example APRA CPS 234 and CPS 230 for financial services, Essential Eight or IRAP for government, and ISO 27001 or SOC 2 expectations |

### AI governance

| Area | Enterprise production |
| --- | --- |
| Model governance | Approved model list per client, model change process (including Bedrock model version deprecations), evals re-run before any model switch |
| Quality gates | Minimum eval scores as release criteria for packs and prompts; ongoing monitoring of review outcomes and drift |
| Human oversight | Documented oversight policy: which decisions always need review, reviewer training, escalation paths |
| Responsible AI | Alignment with the client's AI policy and Australia's voluntary AI safety guidance; fairness checks where decisions affect people; transparency notes for end users |
| AI incidents | Process to detect, record, pause and remediate harmful or wrong outputs |

### Reliability, scale and cost

| Area | Enterprise production |
| --- | --- |
| Availability | Multi-AZ runtime, Multi-AZ or Aurora Postgres, agreed RPO and RTO with tested backup and restore |
| Throughput | Bedrock quota planning and throttling handling, provisioned throughput where needed, queue-based processing for spikes; any cross-region inference checked against data residency requirements |
| Cost control | Cost per case tracked, budgets and alerts, model routing and prompt caching tuned against eval results |
| Multi-tenancy (if SaaS later) | Tenant isolation, per-tenant keys and quotas, noisy-neighbour protection |

### Operations and commercial

| Area | Enterprise production |
| --- | --- |
| Support | SLAs, support hours and channels, incident response, runbooks, release notes |
| Enablement | Reviewer and admin training, user guides, change management with client teams |
| Legal and insurance | Master services agreement, data processing agreement, liability terms, professional indemnity and cyber insurance |
