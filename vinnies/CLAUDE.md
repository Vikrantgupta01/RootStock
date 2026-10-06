# Vinnies — working rules

The Vinnies demo domain for Sinew Rootstock (see `../docs/design.md`). Everything
Vinnies-specific lives here; `rootstock-core` never references this folder.

- `vinnies-mcp-server/` — the dummy St Vincent de Paul (Vinnies) application. It plays the
  client's existing system and exposes its functions as MCP tools. Its own Maven project.

All repo-wide rules in `../CLAUDE.md` apply. The rules below are specific to Vinnies.

## Versions

Same as Rootstock, pinned in `vinnies-mcp-server/pom.xml`: Java 21, Spring Boot 4.1.1,
Spring AI 2.0.1 (MCP SDK 2.0.0). Run Maven as `mvn` (no wrapper).

Checked against these versions, not guessed:

- MCP annotations are `org.springframework.ai.mcp.annotation.McpTool` / `McpToolParam`;
  tool hints go in `McpTool.McpAnnotations`. The older `org.springaicommunity` package is gone.
- `spring.ai.mcp.server.protocol`: `SSE` | `STREAMABLE` | `STATELESS`. We use `STREAMABLE`
  at `/mcp`. The annotation scanner is on by default: any Spring bean with `@McpTool`
  methods is a tool.
- MCP SDK 2.0: `Tool.inputSchema()` is a plain `Map` (the raw JSON Schema).

## Data

- **All data is fictional.** Suburb names and postcodes are real; everything else is
  invented. Phone numbers come only from ACMA's ranges reserved for fiction:
  `02 5550 xxxx` (households) and `02 7010 xxxx` (services). A test enforces this.
- **Tables come from `vinnies-mcp-server/db/setup.sql`, run by hand.** No Flyway: the app
  never creates or alters tables; Hibernate only validates them on startup
  (`ddl-auto: validate`). A schema change means editing `setup.sql` (keep it
  re-runnable: `IF NOT EXISTS`) and the entities together.
- **Demo shortcut:** the tables live in schema `vinnies_mock` inside RootStock's database
  (`rootstock_app`), using the same login. A real client system would have its own
  database and login.
- Ids are assigned by the app (name-based UUIDs from each row's ref), never by the
  database, so seeding is repeatable.
- Seed data: `./demo-data.sh seed|reset`. After changing the generator, use `reset`:
  `seed` only checks row counts and the first household, so it reports old data as
  "already seeded".

## Tools

Follow the design's tool rules:

- Small and single-purpose, with typed inputs.
- Clear descriptions: they are the LLM's documentation. Say what the output means and
  what to do with it.
- Mark read-only tools `readOnlyHint = true`.
- **Return only the fields the step needs:** no contact details unless the tool's job
  is contact details.
- (From Iteration 10) every write is idempotent and needs an approval id.

## Tests

- **Unit tests** (`*Test`): `mvn test`. Seconds, no database, no AWS.
  `McpEndpointTest` leaves out the database and mocks the beans that need it; add a
  `@MockitoBean` there when a new tool needs a repository.
- **Integration tests** (`*IT`): `mvn verify` with `.env` loaded. They run against RDS
  in a throwaway schema per run (`@Import(ThrowawaySchemaConfig.class)`): created from
  `db/setup.sql`, seeded through `DemoDataLoader.reset()`, dropped at the end. Never point
  a test at `vinnies_mock`.
- Tool tests go through a real MCP client (`McpTestClient`), the way Inspector and
  Rootstock connect.
- A killed test run can leave a `vinnies_test_<timestamp>_<id>` schema behind. Drop it by
  hand (`DROP SCHEMA <name> CASCADE`).
- Tests that call Bedrock are tagged `live` (none yet).

## Commands

```bash
cd vinnies/vinnies-mcp-server
set -a && source .env && set +a        # settings; names in .env.example
mvn spring-boot:run                    # MCP server on http://localhost:8081/mcp
./demo-data.sh seed|reset              # load the fictional data
mvn test                               # unit tests
mvn verify                             # + integration tests on RDS
npx @modelcontextprotocol/inspector    # Streamable HTTP, http://localhost:8081/mcp
```
