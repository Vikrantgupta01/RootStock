# Vinnies

The Vinnies demo domain for Sinew Rootstock. **All data is fictional.**

`vinnies-mcp-server` is a dummy St Vincent de Paul (Vinnies) application. It plays the
client's existing system, a stand-in for the system a real client already runs, and
exposes its functions as MCP tools over HTTP, which is how Rootstock will reach it.
Rootstock itself (`../rootstock-core`) knows nothing about Vinnies.

## Tools

| Tool | Does | Since |
|---|---|---|
| `ping` | Confirms the server is reachable | Iteration 1 |
| `find_household(name, suburb, phone?)` | Up to 5 candidate households, ranked by a 0–1 match score | Iteration 1 |
| `get_assistance_history(householdRef, sinceDays)` | A household's past assistance in the last N days, newest first: date, type, amount; an unknown ref is an error | Iteration 2 |
| `get_assistance_guidelines(assistanceType)` | The guideline for FOOD, ENERGY_BILL or RENT: text, limit per visit, repeat window in days | Iteration 2 |
| `search_local_services(needType, suburb)` | Up to 5 services for a need: address, phone, hours, eligibility. In-suburb first; if none, others flagged `inSuburb: false` | Iteration 2 |

| Resource | Content |
|---|---|
| `vinnies://guidelines/{assistanceType}` | One guideline as Markdown: the same facts the tool returns |
| `vinnies://guidelines` | All guidelines in one Markdown document |

**Security:** every request needs a Cognito access token (`Authorization: Bearer …`). Without
one the server answers **401**. All tools and resources above need scope **`vinnies/read`**
(`ping` only needs a valid token); write tools from Iteration 10 will need `vinnies/write`. A
token without the right scope gets an MCP error, "Access Denied". Get a token with
`./get-token.sh read`.

How `find_household` scores:
- **Phone:** an exact match (any format, `+61` allowed) scores **1.0** and always ranks first.
- **Otherwise:** 0.75 × name similarity, plus 0.2 if the suburb matches, so at most 0.95.
  - Names are fuzzy (Smith/Smyth, Catherine/Katherine, initials) and compared against
    every household member.
  - A shared suburb alone never matches.
- **Output:** never includes phone numbers or other contact details.

## Run it

Needs Java 21+, Maven, Node.js (for MCP Inspector) and access to RootStock's RDS database.

### 1. Settings

```bash
cd vinnies/vinnies-mcp-server
cp .env.example .env      # fill in: same database URL and login as rootstock-core/.env,
                          # the Cognito issuer, and the machine-to-machine client
```

### 2. Database (once)

Run [`db/setup.sql`](vinnies-mcp-server/db/setup.sql) against the `rootstock_app` database,
as the login in `.env`, in any SQL tool. It creates schema `vinnies_mock` and its tables:

- `household`
- `person`
- `assistance`
- `local_service`
- `assistance_guideline`

It's safe to run again: after pulling a version that adds a table, run it again, then
`./demo-data.sh reset`. The app never creates tables itself; it checks them on startup.

### 3. Fictional data

```bash
./demo-data.sh seed       # load the demo data into empty tables (does nothing if already loaded)
./demo-data.sh reset      # wipe and reload: back to a clean demo
```

- **Contents:** 50 households (163 people) across 12 Sydney suburbs, 200 past assistance
  records and 40 local services.
- **Repeatable:** a fixed random seed means the same day always gives the same data. Each
  run logs a fingerprint of every row, so you can compare runs.

### 4. Start the server

```bash
set -a && source .env && set +a
mvn spring-boot:run       # MCP endpoint: http://localhost:8081/mcp (Streamable HTTP)
```

### 5. Try it in MCP Inspector

```bash
./get-token.sh read                       # prints a 60-minute access token
npx @modelcontextprotocol/inspector
```

In the browser:
1. Set **Transport** to *Streamable HTTP* and the URL to `http://localhost:8081/mcp`.
2. Under **Authentication**, add the header `Authorization` with the value `Bearer <token>`.
   Without it, Connect fails: the server answers 401.
3. Choose **Connect**, then **Tools**.
4. Pick `find_household`, then **Run**.

Or from the command line:

```bash
npx @modelcontextprotocol/inspector --cli http://localhost:8081/mcp --transport http \
  --header "Authorization: Bearer $(./get-token.sh read)" \
  --method tools/call --tool-name find_household \
  --tool-arg "name=Linh Tran" --tool-arg suburb=Blacktown
```

Demo scripts: [Iteration 1](docs/demo-1.md) (find a household) and [Iteration 2](docs/demo-2.md) (all read tools, secured).

## Tests

```bash
mvn test                                         # unit tests: seconds, no database or AWS
set -a && source .env && set +a && mvn verify    # + integration tests against RDS
```

Integration tests run in a throwaway schema (`vinnies_test_<timestamp>_<id>`). Each run
builds it from `db/setup.sql`, seeds it, and drops it at the end, so the demo data in
`vinnies_mock` is never touched.

Working rules for this project: [`CLAUDE.md`](CLAUDE.md).
