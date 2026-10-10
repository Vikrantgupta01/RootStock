# RootStock — working rules

Monorepo, each folder built on its own (no root POM):

- `rootstock-core/` — the domain-agnostic framework (Spring Boot backend, Maven).
- `frontend/` — React 19 + Vite.
- `vinnies/` — the Vinnies demo domain: `vinnies-mcp-server/` (dummy client app exposing MCP
  tools, own database `vinnies_mock`). Its own Maven project; see `vinnies/CLAUDE.md`.

## Pinned versions

Do not change these without explicit approval.

| | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| LangGraph4j | 1.9.3 |
| AWS SDK for Java v2 | 2.51.2 |
| Build | Maven, run as `mvn` (the repo has no `mvnw` script) |

## Rules

- **One configuration, no Spring profiles.** Don't add `application-*.yml`, `@Profile` or
  `@ActiveProfiles`. Behaviour differences come from settings, not profiles.
- **Everything runs against AWS**: RDS (PostgreSQL), Cognito, Bedrock (models and
  Knowledge Base), S3. Plus Langfuse for tracing. There are no local or fake substitutes.
- **Settings come from a gitignored `.env`.** A committed `.env.example` lists every
  variable **by name only**, with no values. Never commit `.env` or put secrets in
  `application.yml`.
- **No docker-compose and no Testcontainers**, in the app or in tests.
- **Integration tests use a throwaway schema in RDS**, created for the run and dropped
  afterwards. They never touch the application's own schema.
- **Tests that call Bedrock are tagged `live`** (`@Tag("live")`), so they can be included
  or excluded explicitly.
- **`rootstock-core` contains no Vinnies code and never references `vinnies/`.** All Vinnies
  code lives under `vinnies/`, which may depend on Rootstock but never the reverse.
- **`core` never depends on `autoconfig` or `runtime`** (see the package plan below).
- **Every call into a client system goes through `ToolGateway`** (`com.rootstock.core.tools`).
  No code calls an MCP client directly, and the Spring AI MCP client starter is not used: it
  would hand remote tools straight to the chat model. Connections, tools and per-node
  allowlists come from a domain pack's `tools.yaml` via `ROOTSTOCK_TOOLS_FILE`, never from
  `rootstock-core`. WRITE tools may only be allowed in write nodes (checked at startup).
- **No writes without approval.** Ask before committing, pushing, or changing anything
  outside the working tree. That includes AWS resources (RDS data or schema, Cognito users
  and groups, S3 objects, Knowledge Base syncs) and Langfuse data.

## Package plan

Layout under `com.rootstock`. Dependencies point inwards only:
`runtime → autoconfig → core`. `ArchitectureTest` (ArchUnit) enforces this, that every
class is in one of the three, and that nothing in `src/main` mentions Vinnies.

| Package | Holds |
|---|---|
| `core` | Domain and logic: agent graph, RAG, conversations, tools, ontology, packs. It imports neither `autoconfig` nor `runtime`. |
| `autoconfig` | Spring wiring: `@Configuration`, `@ConfigurationProperties`, bean definitions that assemble `core`. |
| `runtime` | The running application: controllers, security filters, tracing aspects, platform status. |

`RootStockApplication` (the `main` class) stays at `com.rootstock` so component scanning
covers all three layers; it is the only class outside them.

## Domain packs and the ontology

- A pack is a folder of YAML (`ontology.yaml`, `tools.yaml`, …) found through
  `ROOTSTOCK_PACKS_PATHS`. Rootstock ships none.
- `ontology.yaml` extends the core ontology in
  `src/main/resources/ontology/rootstock-core.yaml` (refer to its concepts as `core.X`).
  Changing the core ontology changes every pack: treat it as public API.
- Schemas and glossaries are **generated** from the ontology, never written by hand.
  A pack's `generated/` files are snapshot-tested by `PackSnapshotTest`; after an intended
  change run `mvn test -Dtest=PackSnapshotTest -Dsnapshot.update=true` and review the diff.
- A broken pack must never stop Rootstock: it is listed INVALID with its problems.

## Not yet compliant

These are known gaps between the current code and the rules above. Don't extend them;
fix them when the related area is touched.

- There is one integration test so far (`ThrowawaySchemaIT`) and one `live` test
  (`BedrockChatLiveIT`); everything else mocks the database and Bedrock. New
  integration tests use `ThrowawaySchemaConfig`.

## Commands

```bash
cd rootstock-core
set -a && source .env && set +a     # load settings
mvn -q -DskipTests compile
mvn test                            # unit tests, no database or AWS
mvn verify                          # + integration tests on a throwaway RDS schema
mvn verify -Dlive.excluded=none -Dgroups=live   # only tests that call Bedrock
mvn spring-boot:run                 # http://localhost:8080

cd frontend && npm run dev          # http://localhost:5173, proxies /api to :8080
npm run build && npm run lint
```
