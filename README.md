# RootStock

AI-powered web app. Java 21 / Spring Boot backend with Spring AI wired to **AWS
Bedrock**, a PostgreSQL database for app data (conversations, documents,
profiles, jobs), and a React + TypeScript frontend.

Three AI surfaces, each with its own server-side conversation history:

| Surface | Endpoint | What it does |
|---|---|---|
| **Chat** | `POST /api/chat` | Plain assistant: one model call per message, optionally streamed. |
| **Knowledge base / RAG** | `POST /api/rag/query` | Fixed pipeline over a tenant-scoped **AWS Bedrock Knowledge Base**: rewrite the follow-up → retrieve once → answer with citations. Document ingestion, tunable query profiles, optional reranking. |
| **Agent** | `POST /api/agent` | A **ReAct agent** built with **LangGraph4j**: the model decides whether to search the knowledge base, with what wording and how many times, before answering — and returns the steps it took. |

Also ships a `Customer` CRUD sample, Cognito authentication with roles and
access groups, and LLM tracing to **Langfuse**. For how to run and test all of
it, see **[TESTING.md](TESTING.md)**.

The work follows the iterations in **[docs/design.md](docs/design.md)**. The
first demo domain, a dummy St Vincent de Paul (Vinnies) app that exposes MCP
tools, lives in **[vinnies/](vinnies/README.md)**, with its own run steps.

## Layout

```
RootStock/
├── rootstock-core/   Spring Boot 4 · Java 21 · Maven · Spring AI 2 (Bedrock Converse) · LangGraph4j · JPA · Flyway
├── frontend/         React 19 · TypeScript · Vite · React Router · TanStack Query
├── vinnies/          Vinnies demo domain (fictional data); never referenced by rootstock-core
│   ├── vinnies-mcp-server/   dummy Vinnies app exposing MCP tools (find_household, …)
│   └── vinnies-pack/         Rootstock's Vinnies pack: ontology.yaml, tools.yaml, generated/
└── docs/design.md    Sinew Rootstock design and iteration plan
```

Inside `rootstock-core`, code is in three layers under `com.rootstock`, and
dependencies point inwards only (`runtime → autoconfig → core`, enforced by
`ArchitectureTest` with ArchUnit):

| Package | Holds |
|---|---|
| `core` | Domain and logic: agent graph, RAG, conversations, tools, ontology, packs |
| `autoconfig` | Spring wiring: `@Configuration`, properties, beans that assemble `core` |
| `runtime` | The running app: controllers, security filters, tracing aspects, status |

`RootStockApplication` stays at `com.rootstock` so component scanning covers all three.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | Project targets Java 21; a newer JDK on `PATH` is fine. |
| Maven | 3.9+ | The repo has no Maven wrapper script, so use an installed `mvn`. |
| Node.js | 20+ | For the frontend. |
| PostgreSQL on AWS RDS/Aurora | — | The app's own database (conversations, documents, profiles, jobs). Flyway creates and migrates the tables on startup. There's no local database. |
| AWS account + credentials | — | Required for chat, the agent and the RAG feature — there's no offline/fake mode for either anymore. Needs a Bedrock Knowledge Base (Aurora PostgreSQL vector store) already provisioned, an S3 bucket as its data source, model access for the configured chat model, and an IAM identity with the narrow set of Bedrock/S3 permissions the app needs (see `rootstock-rag-app-policy` pattern — never run this as an AWS root/admin identity). Provide credentials via the standard AWS chain (an SSO/named profile is the simplest for local dev). |

## Run it

### 1. Database

Point `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` at your RDS/Aurora PostgreSQL
database (in `rootstock-core/.env`). There is nothing to start locally: Flyway
applies the migrations in `src/main/resources/db/migration` when the backend
starts.

### 2. Backend

```bash
cd rootstock-core
# AWS credentials + region, and the Bedrock Knowledge Base / data source ids
# (see Configuration below), are required for chat, the agent and RAG to
# actually do anything. A gitignored rootstock-core/.env works well for local
# dev -- see TESTING.md:
#   set -a && source .env && set +a
mvn spring-boot:run
```

- `GET  http://localhost:8080/api/health` → `{ "status": "UP", ... }`
- `GET  http://localhost:8080/actuator/health`
- `POST http://localhost:8080/api/chat` with `{ "message": "hello" }` →
  `{ "reply": "...", "conversationId": "..." }` (503 with a clear message until
  AWS Bedrock is configured). Send that `conversationId` back to continue the
  thread — see [Conversations](#conversations).
- `POST http://localhost:8080/api/chat` with `Accept: text/event-stream` → token stream
- `POST http://localhost:8080/api/agent` with `{ "message": "..." }` →
  `{ "answer": "...", "conversationId": "...", "iterations": 2, "steps": [...] }` — see [Agent](#agent)
- `POST http://localhost:8080/api/rag/query` — see [Knowledge base / RAG](#knowledge-base--rag)

All of these except `/api/health` need a Cognito ID token — see [Authentication](#authentication).

### 3. Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

The Vite dev server proxies `/api/*` to `http://localhost:8080`, so no CORS setup
is needed locally. For non-dev builds set `VITE_API_BASE_URL` (see `.env.example`).

Pages: **Chat** (`/`), **Agent** (`/agent`), **Knowledge base** (`/knowledge`) and, for
admins, **Tools** (`/tools`, the Tool explorer; see [Client systems](#client-systems-mcp-and-the-toolgateway)).

## Configuration

Backend config lives in `rootstock-core/src/main/resources/application.yml`. Key knobs
(all overridable by environment variable):

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | _(required: no local default)_ |
| `spring.datasource.username` / `.password` | `DB_USERNAME` / `DB_PASSWORD` | _(required)_ |
| `spring.ai.bedrock.aws.region` | `AWS_REGION` | `us-east-1` |
| `spring.ai.bedrock.converse.chat.options.model` | `BEDROCK_MODEL` | `us.anthropic.claude-sonnet-4-5-20250929-v1:0` |
| `rootstock.rag.bedrock.knowledge-base-id` | `RAG_BEDROCK_KB_ID` | _(your Knowledge Base id)_ |
| `rootstock.rag.bedrock.data-source-id` | `RAG_BEDROCK_DATA_SOURCE_ID` | _(your Knowledge Base's S3 data source id)_ |
| `rootstock.rag.blob.backend` | `RAG_BLOB_BACKEND` | `s3` (real AWS S3 — required; see below) |
| `rootstock.rag.blob.s3.bucket` | `RAG_S3_BUCKET` | the S3 bucket your Knowledge Base's data source reads from |
| `rootstock.agent.max-iterations` | `AGENT_MAX_ITERATIONS` | `6` (Reason steps — model calls — per agent question) |
| `rootstock.observability.langfuse.enabled` | `LANGFUSE_ENABLED` | `true` (no-op until both keys are set) |
| `rootstock.observability.langfuse.host` | `LANGFUSE_HOST` / `LANGFUSE_BASE_URL` | `https://us.cloud.langfuse.com` |
| `rootstock.observability.langfuse.public-key` / `.secret-key` | `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | _(blank — keep in `.env`)_ |
| `management.tracing.sampling.probability` | `LANGFUSE_SAMPLE_RATE` | `1.0` |
| `rootstock.observability.langfuse.environment` | `LANGFUSE_ENVIRONMENT` | `development` |
| `spring.config.import` (`rootstock.tools.*`) | `ROOTSTOCK_TOOLS_FILE` | _(unset: no client systems)_ path to a domain pack's `tools.yaml` |
| `rootstock.packs.paths` | `ROOTSTOCK_PACKS_PATHS` | _(unset: no packs)_ comma-separated folders of domain packs, e.g. `vinnies/vinnies-pack/packs` |
| `rootstock.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |

> Set `BEDROCK_MODEL` to a model or inference-profile id your AWS account has
> **model access** for in the chosen region — this can change over time as
> providers deprecate models (Bedrock console → model catalog shows each
> model's lifecycle status: `ACTIVE` vs `LEGACY`). An un-granted, retired, or
> marketplace-gated id returns an error surfaced by the API as
> `503 AI backend unavailable`.
>
> `rootstock.rag.blob.backend` defaults to `s3` because Bedrock Knowledge Base
> ingestion requires real S3 — there's no local/offline substitute (the
> `filesystem` backend still exists for unit tests, but documents written
> there are invisible to Bedrock and will never actually get indexed).

## Authentication

Every `/api/**` route needs a valid **AWS Cognito ID token** as
`Authorization: Bearer <token>`. The only exceptions are `POST /api/auth/login`,
`POST /api/auth/refresh` and `GET /api/health` (liveness, so the UI can tell
"backend down" from "not signed in").

- `POST /api/auth/login` `{email, password}` → the backend calls Cognito's
  `InitiateAuth` server-side and returns Cognito's own tokens. The frontend
  never talks to AWS directly and holds no AWS credentials.
- `POST /api/auth/refresh` `{refreshToken}` → a fresh ID token.
- `GET /api/auth/me` → the caller's id, email, tenant, role and groups, as the
  verified token describes them. This is what the UI gates itself on.

Tokens are validated against the user pool's JWKS and pinned three ways: to the
pool (`iss`), to this app client (`aud`), and to **ID** tokens (`token_use`).
The last one matters — Cognito signs access tokens with the same keys, and on
this pool's plan an access token carries neither `custom:tenant_id` nor
`custom:role`, so accepting one would authenticate a tenantless request.

**Roles** (the `custom:role` attribute, mapped to a Spring Security authority):

| Role | Can |
| --- | --- |
| `VIEWER` | query, browse documents, download |
| `EDITOR` | + upload, add versions, reindex, delete |
| `ADMIN` | + profile tuning, group and document-access management; sees every document in the tenant regardless of access groups |

**Access groups** (Cognito groups, arriving as the `cognito:groups` claim)
decide what a caller can *see* rather than what they can *do*. A document
tagged with one or more groups is only retrievable by their members; a document
with no tags carries a synthetic `__public__` group every caller also carries,
so it stays visible tenant-wide. Enforcement is a `listContains` clause ANDed
into the Bedrock `Retrieve` filter, applied server-side by Bedrock — an
excluded chunk is never returned, so the chat model never sees it and there is
nothing to leak through the answer.

Changing a document's grants (`PUT /api/rag/documents/{id}/access-groups`)
rewrites the S3 `.metadata.json` sidecar and queues a re-sync, because Bedrock
filters on its own copy of the grants and only re-reads it on the next
ingestion job. **Until that job finishes, the previous grants still apply.**

Configuration (`rootstock.auth.cognito.*`, all overridable by env var):
`region`/`AWS_REGION`, `user-pool-id`/`COGNITO_USER_POOL_ID`,
`client-id`/`COGNITO_CLIENT_ID`. These are public OIDC identifiers, not
secrets. The app's IAM identity needs `cognito-idp:InitiateAuth` plus the
group-admin actions (`CreateGroup`, `ListGroups`, `AdminAddUserToGroup`,
`AdminRemoveUserFromGroup`), scoped to that one pool.

**Creating users.** `POST /api/rag/users` (ADMIN only) takes
`{email, password, role, groups?}` and creates the user in Cognito, ready to
sign in — the password is set as permanent, because this app answers no auth
challenges and a `FORCE_CHANGE_PASSWORD` account could never get past login.
There is deliberately **no tenant field**: the backend takes it from the
caller's token, so an admin can hand out any role, including `ADMIN`, but only
ever inside the tenant they already administer. The Access tab has a form for
it.

A tenant's **first** admin can't come from there — with no admin yet, nothing
could authorize the call. That stays an out-of-band `aws cognito-idp
admin-create-user` + `admin-set-user-password` (setting `custom:tenant_id` and
`custom:role=ADMIN`), gated by IAM rather than by this application. There is no
self-service sign-up.

## Conversations

`/api/chat`, `/api/rag/query` and `/api/agent` are all conversational. Send the
`conversationId` from a response back with the next message to continue the
thread; omit it to start a new one. History is **stored server-side** (the
`conversation` and `chat_message` tables), so it survives a page refresh, a
restart and more than one instance — and can't be rewritten by whoever holds
the token, which a client-supplied transcript could be.

A thread belongs to one person: every lookup is by (id, tenant, Cognito `sub`)
together, so an id from somewhere else resolves to a 404 rather than to someone
else's conversation. Chat, RAG and agent threads are kept apart (the
conversation's `kind`) — they answer under different prompts, so continuing one
as another is a 400.

- `GET /api/chat/conversations`, `GET /api/rag/conversations`,
  `GET /api/agent/conversations` — your threads, newest activity first, titled
  from their opening message.
- `GET …/conversations/{id}` — the stored transcript.
- `DELETE …/conversations/{id}` — messages cascade.

Only the question and the final answer of each exchange are stored — not RAG
citations, not the agent's intermediate steps.

**Only the last 20 messages** (about 10 question/answer exchanges) ride along
with a new message (`ConversationService.HISTORY_TURNS`). That's a cost and
context-window bound, not a nicety: every message is re-sent on every request
and Bedrock charges for all of it. The cut never leaves an answer at the start
whose question was dropped.

**RAG follow-ups get rewritten before retrieval.** Similarity search only sees
the text it's given, so *"and what about its price?"* would match nothing —
the thing being priced is in an earlier turn. A condense step turns the
follow-up into a standalone query first, and the response's `retrievalQuery`
says what was actually searched for, so the rewrite is inspectable rather than
invisible. It costs one extra model call per follow-up, and none on the first
message of a thread. The answer is still generated against the question as
typed; only retrieval uses the rewrite.

## Agent

`POST /api/agent` `{ message, conversationId? }` runs a **ReAct** (Reason + Act)
agent. Instead of a fixed pipeline, the model decides what to do next: call a
tool, read the result, then call another or answer. The loop is an explicit
[LangGraph4j](https://github.com/langgraph4j/langgraph4j) state graph
(`com.rootstock.core.agent.AgentGraph`):

```
START → agent (Reason) ──asks for tools?──yes→ tools (Act + Observe) ─┐
           ▲                                                            │
           └────────────────────────────────────────────────────────────┘
           └─no, or out of iterations→ END
```

- **agent** — one Bedrock call with the transcript and the tool definitions.
  The model either requests tool calls or answers.
- **tools** — runs every requested call and appends the results for the next
  Reason step. A failing or unknown tool comes back to the model as an error
  message it can react to, rather than failing the request.
- The loop stops after `rootstock.agent.max-iterations` Reason steps (default
  6); the last step is told to answer with what it has.

**Tools** (`AgentTools`):

| Tool | Does |
|---|---|
| `searchKnowledgeBase(query)` | Searches the Bedrock Knowledge Base with the **same tenant, active-version and access-group filter** as `/api/rag/query`, so the agent can't see — or leak — what the caller can't. Returns numbered passages with their source document. Uses the `rootstock.rag.defaults` top-k and threshold, not a RAG profile, and no reranker. |
| `currentDateTime()` | Current UTC date, time and weekday. |

Adding a tool is a `@Tool` method on `AgentTools`; it's offered to the model
automatically.

**Response**: `{ answer, conversationId, iterations, steps[] }`, where each step
is `{ iteration, thought, tool, input, observation }` — what the model said it
needed, which tool it called with what arguments, and what came back
(abbreviated to 1,000 characters; the model saw it in full). History keeps only
the question and the final answer.

**Which surface to use**: the agent costs more (one model call per Reason step)
and is less predictable than `/api/rag/query`, but it can search more than once,
reword a search that missed, and combine sources. Prefer RAG when you want fixed
cost, profile-tuned retrieval and structured citations.

**Implementation notes**:
- The graph calls Spring AI's `ChatModel` directly, not `ChatClient`. In
  Spring AI 2 a `ChatModel` returns tool calls unexecuted, while `ChatClient`
  adds an advisor that runs the whole tool loop itself — the loop this graph
  exists to make explicit.
- LangGraph4j's state cloning (for checkpoints) is turned off: it uses Java
  serialization, which Spring AI messages don't support, and no checkpointer is
  used.
- LangGraph4j runs nodes on `ForkJoinPool.commonPool`. Tenant and groups reach
  the tools through Spring AI's `ToolContext`, captured on the request thread,
  and the tracing context is restored around each node so the run stays one
  Langfuse trace.

**Frontend** — the `/agent` page: chat layout, with each answer carrying a
collapsed *"N tool calls · M steps"* summary that expands into the Reason / Act /
Observe trail. Steps are only shown for answers given in the current page view,
since the server doesn't store them.

## Knowledge base / RAG

Tenant-scoped document ingestion + retrieval, backed entirely by an **AWS
Bedrock Knowledge Base**. Tenant, role and group membership come from the
signed-in user's Cognito ID token — see [Authentication](#authentication).

This app is a thin control plane around Bedrock, not a vector database itself:
parsing, chunking, embedding, storage, and similarity search all happen inside
AWS. What this app owns is the document/version/job bookkeeping (in its own
Postgres) and the tenant-scoping contract with Bedrock (a `tenant_id`, plus
`document_id`/`document_version_id`, tagged onto every uploaded object via an
S3 `.metadata.json` sidecar and filtered on at query time).

**Documents & versions**
- `POST /api/rag/documents` (multipart `file`, optional `sourceKey`, `displayName`)
  → uploads the file to S3 under a key scoped to this exact tenant/document/
  version (`rag-kb/<tenantId>/<documentId>/v<versionNo>`), plus a
  `.metadata.json` sidecar Bedrock's data source reads for tenant filtering.
  This same object serves both downloads and Bedrock ingestion — no separate
  content-addressed copy. Creates version 1, queues a sync job. Re-POST the
  same `sourceKey`, or `POST /api/rag/documents/{id}/versions`, to add a
  version.
- Background poller triggers a Bedrock Knowledge Base data-source sync
  (Bedrock owns parsing/chunking/embedding entirely) and waits for it to
  report success — including a check that it actually processed something,
  not just "completed" over an empty/misconfigured data source. Watch
  progress at `GET /api/rag/jobs`.
- `POST /api/rag/documents/{id}/versions/{n}/activate` — roll forward/back;
  `.../reindex` re-syncs; `GET .../content` downloads; `DELETE` purges the
  Bedrock-facing S3 object and triggers a cleanup sync.

**Query profiles** — named, versioned bundles of query-time knobs only
(chunking/embedding are fixed at the Bedrock Knowledge Base/data-source level,
not per-profile): top-k, similarity threshold, chat model id and max context
tokens (editable, not yet wired to behavior — see Roadmap), prompt template,
and optional reranking.
- `GET/POST /api/rag/profiles`, `GET /api/rag/profiles/{id}`,
  `GET/POST /api/rag/profiles/{id}/versions` (editing makes a new version).
- `POST /api/rag/profiles/{id}/activate` — always an immediate pointer flip;
  there's no re-indexing to wait for anymore since a profile no longer
  controls how documents are chunked/embedded.

**Query** — `POST /api/rag/query`
`{ question, conversationId?, profileId?, topK?, similarityThreshold? }`
→ `{ answer, grounded, citations[], conversationId, retrievalQuery, profileId, … }`.
Calls Bedrock's `Retrieve` API (filtered to the tenant's active document
versions and the caller's access groups, optionally reranked — see the profile's
reranker settings), grounds the profile's prompt template in the results, and
answers via the Bedrock chat model. Follow-ups are supported — see
[Conversations](#conversations).

Config (`rootstock.rag.*` in `application.yml`, all env-overridable — see the
Configuration table above for the Bedrock/blob-store keys):

| Setting | Env | Default |
|---|---|---|
| Ingestion poller | `rootstock.rag.ingest.poller-enabled` | `true` |
| Reranker model (when a profile enables it) | — | `cohere.rerank-v3-5:0` (only model currently available in this account/region) |

**Frontend** — the `/knowledge` page (React) drives all of the above:
- **Documents** tab — drag-and-drop upload with progress; expandable version rows
  (activate / reindex / download / delete); add-a-version dropzone per document.
- **Tuning** tab — edit a profile's top-k / similarity threshold / reranker /
  prompt template / chat model override / max context tokens (the last two
  save but don't affect behavior yet — see Roadmap), "Save version" vs
  "Save & activate" (always immediate now), version history with per-version
  activate.
- **Playground** tab — ask a question, see the grounded answer with citation
  chips; optional top-k / threshold overrides.
- **Activity** tab — the ingestion job feed, auto-refreshing while work is queued.

The header shows who you are signed in as (email, role, tenant, groups) and a
sign-out button. The tenant is no longer typed in — it comes from the token.
Viewers don't see the upload dropzone or the version actions; only admins see
the **Tuning** and **Access** tabs.

## Client systems (MCP) and the ToolGateway

Rootstock reaches a client's own system only through **MCP tools**, and only
through one class: **`ToolGateway`** (`com.rootstock.core.tools`). Rootstock ships
no client: which systems to connect to, their tools and who may call what come
from a domain pack's `tools.yaml`, named by `ROOTSTOCK_TOOLS_FILE` in `.env`. The
Vinnies one is `vinnies/vinnies-pack/packs/vinnies/tools.yaml`.

```yaml
rootstock:
  tools:
    connections:            # one MCP server per client system
      vinnies: { url: ..., token-url: ..., client-id: ${...}, client-secret: ${...},
                 read-scope: vinnies/read, write-scope: vinnies/write }
    tools:                  # logical name -> connection, READ or WRITE
      - { name: find_household, connection: vinnies, access: READ }
    allowlists:             # graph node -> the tools it may call
      enrich: [find_household, get_assistance_history, ...]
      commit: []
    write-nodes: [commit]   # the only nodes where WRITE tools may ever be allowed
```

- **Connections:** the MCP SDK over Streamable HTTP, with OAuth2 client-credentials
  tokens (Spring Security's OAuth2 client), cached until a minute before expiry.
  Read and write tokens are separate, so a write token is requested only when a
  write tool runs. At startup Rootstock lists each server's tools. A server that is
  down is logged, not fatal: its session opens on first use.
- **The gateway** checks that the tool exists and the calling node may use it, and
  only then calls it. Each call returns a typed result (`OK`, `BLOCKED`,
  `UNKNOWN_TOOL`, `TOOL_ERROR`, `UNAVAILABLE`), never an exception. A blocked call
  never reaches the client system.
- **Checked at startup:** unknown tools in an allowlist, unknown connections, and
  **any WRITE tool allowed outside a write node** stop the app; a configured tool
  the server doesn't offer is logged as a warning.
- **Not the Spring AI MCP client starter:** it would hand every remote tool to the
  chat model directly, bypassing the gateway.

**Tool explorer** (`/tools`, admins only): pick a node and a tool, fill in the
tool's own input form, run it through the gateway. A tool outside the node's
allowlist is refused, exactly as in a run. `GET /api/tools` lists nodes, allowlists
and each tool as the client system describes it; `POST /api/tools/call` takes
`{node, tool, arguments, caseId?}` and returns the result with a Langfuse trace
link. The acting user is always the signed-in user.

## Ontology and domain packs

A **domain pack** is a folder of YAML that adapts Rootstock to one client:
`ontology.yaml` (its concepts), `tools.yaml` (its client system's MCP tools),
and later its graph, agents and rules. Rootstock ships none and never names a
domain; it reads every pack under `ROOTSTOCK_PACKS_PATHS` at startup and logs
what it found.

Each pack's `ontology.yaml` **extends the core ontology**
(`rootstock-core/src/main/resources/ontology/rootstock-core.yaml`: Party,
Document, Case, Request, Action, Issue, Approval). It declares:

- **entities** with attributes (`type` or `vocab`, `required`, `many`, `min`,
  `max`, `pii`) and relations (`to`, `many`, `min`, `field`), each extending a
  core concept (`extends: core.Case`);
- **vocabularies**: codes with a definition and synonyms;
- **constraints**: `when` / `require` conditions across fields;
- **projections**: views rooted at one entity, which **embed** related
  entities (filled in from the input) or **reference** them by id.

From that Rootstock generates, per projection, the **JSON Schema** an agent's
structured output must match, and a **prompt glossary** of the vocabularies it
uses (codes, definitions, synonyms).

**Checked on load, with clear messages:** the loader rejects unknown keys,
wrong value types, duplicate keys and invalid YAML (with the line); the
validator rejects unknown concepts, entities and vocabularies, values that are
not codes of their vocabulary, constraint paths that don't exist, projections
that cannot reach what they embed, a synonym used by two codes, and more. Every
problem is reported at once, with where it is (`entities.Need.attributes.category.vocab:
unknown vocabulary 'NeedCategories'; known: [...]`). **A broken pack is listed
as INVALID with its problems; Rootstock keeps running** and doesn't use it.

**Ontology explorer** (`/ontology`): the packs found with their status, each
pack's concepts (inherited fields marked), vocabularies, constraints and
projections, with the generated schema and glossary side by side. Admins can
**Reload packs** after editing a file, with no restart. Endpoints:
`GET /api/ontology`, `GET /api/ontology/core`, `GET /api/ontology/packs/{name}`,
`GET /api/ontology/packs/{name}/projections/{projection}` (schema and glossary),
`POST /api/ontology/reload` (ADMIN).

**Generated files are snapshot-tested.** A pack keeps its generated schema and
glossary in `generated/`; `PackSnapshotTest` fails when they no longer match its
ontology. After an intended change, regenerate them and review the diff:
`mvn test -Dtest=PackSnapshotTest -Dsnapshot.update=true` (with `.env` loaded).

## Platform status

`/status` (any signed-in user) checks, on demand, every service Rootstock
depends on with a real round trip: **Bedrock** (a one-word model call),
**RDS** (a query, plus the latest Flyway migration), **Cognito** (the pool's
signing keys), **Langfuse** (the keys, plus a link to the latest trace) and
each **MCP connection** (its tool list). The checks run in parallel, each with
a 15-second limit, so one slow service turns red instead of hanging the page.
`GET /api/status` returns the same as JSON.

## Observability (Langfuse)

LLM work is traced to [Langfuse](https://langfuse.com) over OpenTelemetry (OTLP
over HTTP). Set `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` in
`rootstock-core/.env`. Without both keys, OpenTelemetry stays switched off
entirely, so a key-less environment exports nothing and logs nothing.

All tracing lives in `com.rootstock.runtime.observability.TracingAspect`, as aspects, so
the traced code doesn't know it's traced. One trace per request:

| Trace | Contains |
|---|---|
| `chat-response` | the Bedrock generation (model, tokens, cost) |
| `answer-question` (RAG) | the condense generation (follow-ups only), `retrieve-context` (query, hits, scores), the answer generation |
| `agent-run` | per Reason step a generation, per Act step a `tool-<name>` span (and `retrieve-context` under knowledge-base searches) |
| `explore-tool` | the Tool explorer request, with its gateway call as a `tool-<name>` span |

**Client-system tool calls** (every `ToolGateway.call`) are `tool-<name>` spans of
type `tool`, with the arguments as input, the result or refusal reason as output,
and span metadata `node`, `status`, `connection`, `durationMs`, `caseId` and
`actingUser`. `BLOCKED` and `UNKNOWN_TOOL` are level **WARNING**; `TOOL_ERROR` and
`UNAVAILABLE` are **ERROR**, with the reason as the status message. A call made
outside any request starts its own `call-tool` trace with the case id as its
session; a Tool explorer call with a case id uses it as the session too.

Each trace carries the conversation as its Langfuse **session**, the Cognito
`sub` as its **user** (not the email), and tenant/role/model as metadata.
Framework spans (HTTP server, Spring Security, scheduled tasks) are suppressed
so the application's span is always the trace root.

## Roadmap

Not yet functional, but with groundwork already in place so a future release
doesn't need a schema/UI change to add them:

- **Per-profile chat model** (`chatModelId`) — the Tuning tab lets you set an
  override per profile and it's saved with the profile, but every query still
  answers via the single globally configured `BEDROCK_MODEL`. Wiring this up
  means `RagQueryService` passing the profile's model id through to
  `ChatService` instead of always using the default `ChatClient`.
- **Context-token budgeting** (`maxContextTokens`) — same story: editable and
  saved, not yet enforced. Wiring this up means truncating/prioritizing
  retrieved chunks in `RagQueryService.renderContext()` against a token
  budget instead of concatenating everything retrieved.

Agent follow-ups worth doing:

- **Persist agent steps** with the answer, so they survive a page reload.
- **Stream** the steps and answer as they happen, instead of returning
  everything at the end.
- **Use the active RAG profile** (top-k, threshold, reranker) for
  `searchKnowledgeBase`, instead of the global defaults.

## Tests

```bash
cd rootstock-core
set -a && source .env && set +a
mvn test                                        # unit, controller slice, architecture, snapshot tests; no database or AWS
mvn verify                                      # + integration tests (*IT) on a throwaway RDS schema
mvn verify -Dlive.excluded=none -Dgroups=live   # only the tests that call Bedrock (tagged live)
cd ../frontend && npm run build && npm run lint
```

- **Integration tests** (`*IT`, run by `mvn verify`) start the app against
  RDS in a schema of their own, `rootstock_test_<timestamp>_<id>`, which Flyway
  migrates and the run drops afterwards (`ThrowawaySchemaConfig`). They never
  touch the application's schema. A killed run can leave one behind; drop it by
  hand (`DROP SCHEMA <name> CASCADE`).
- **Live tests** call Bedrock, so they cost money: they're tagged `live` and
  excluded unless asked for, as above.
- **Architecture** (`ArchitectureTest`): `core` doesn't depend on `autoconfig`
  or `runtime`, `autoconfig` not on `runtime`, every class is in a layer, and
  nothing in `src/main` mentions a demo domain.
- **Snapshots**: the schema and glossary generators are checked against
  committed files (`src/test/resources/ontology/snapshots/`, and each pack's
  `generated/`); refresh with `-Dsnapshot.update=true` and review the diff.

See **[TESTING.md](TESTING.md)** for what each suite covers and a full manual
walkthrough (API + UI) of the Customer and RAG features. The agent's graph
(`AgentGraphTest`) is tested against a scripted model — the Reason/Act/Observe
loop, the iteration cap, unknown tools, and that the trace context reaches the
worker thread.

## Not yet included

SSO/federation to an enterprise IdP, password reset, MFA, audit logging (all
Cognito configuration layered onto what's here rather than rewrites), CI,
deployment manifests, and Bedrock model fine-tuning. Each is its own follow-up.

Conversation history is capped by turn count rather than tokens, and old threads
are never pruned — both fine at this size, both worth revisiting before real
traffic.
