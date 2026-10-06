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
- **No writes without approval.** Ask before committing, pushing, or changing anything
  outside the working tree. That includes AWS resources (RDS data or schema, Cognito users
  and groups, S3 objects, Knowledge Base syncs) and Langfuse data.

## Package plan

Target layout under `com.rootstock`. Dependencies point inwards only:
`runtime → autoconfig → core`.

| Package | Holds |
|---|---|
| `core` | Domain and logic: agent graph, RAG, conversations, tools. It imports neither `autoconfig` nor `runtime`. |
| `autoconfig` | Spring wiring: `@Configuration`, `@ConfigurationProperties`, bean definitions that assemble `core`. |
| `runtime` | The running application: `main`, controllers, security filters, tracing aspects. |

The current code is organised by feature (`agent`, `auth`, `chat`, `rag`, …), not yet
by this plan.

## Not yet compliant

These are known gaps between the current code and the rules above. Don't extend them;
fix them when the related area is touched.

- `RequestTrace` reads the active Spring profile to name the Langfuse environment
  (`development` when there is none). Nothing sets a profile any more, so this is
  always `development`.
- There is no `.env.example` for `rootstock-core`; only `frontend/.env.example` exists.
- **There are no integration tests.** The Testcontainers-based suite was removed
  along with Docker (2026-10-06). New ones should use the throwaway RDS schema rule
  above. There are no `live`-tagged tests yet either: every test mocks Bedrock.

## Commands

```bash
cd rootstock-core
set -a && source .env && set +a     # load settings
mvn -q -DskipTests compile
mvn test
mvn spring-boot:run                 # http://localhost:8080

cd frontend && npm run dev          # http://localhost:5173, proxies /api to :8080
npm run build && npm run lint
```
