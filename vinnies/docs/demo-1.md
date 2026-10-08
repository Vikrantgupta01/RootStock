# Demo 1: the Vinnies app and its first tool

**Story (about 3 minutes):** the client's system is reachable over MCP, and an agent can
already find the right household from a volunteer's rough details. It handles misspellings,
two families with the same name, and a phone number when there is one. It never hands back
contact details. All data is fictional.

> **Since Iteration 2 the server needs a token.** Run `./get-token.sh read` and add the header
> `Authorization: Bearer <token>` in Inspector before Connect; see [demo-2.md](demo-2.md).

## Before the demo (about 2 minutes)

```bash
cd vinnies/vinnies-mcp-server
./demo-data.sh reset                      # clean, known data (log shows households=50 … fingerprint=…)
set -a && source .env && set +a
mvn spring-boot:run                       # wait for "Started VinniesMcpServerApplication"
```

In a second terminal:

```bash
npx @modelcontextprotocol/inspector       # opens the Inspector in the browser
```

In the Inspector, set **Transport** to *Streamable HTTP* and the URL to
`http://localhost:8081/mcp`, then choose **Connect**.

## Script

1. **The client's system, over MCP (20 s).** Open **Tools** and choose **List Tools**.
   - *Say:* "This is a stand-in for Vinnies' own system. It exposes small tools an agent
     can call. Each has a description written for the AI, and it's marked read-only."
   - Run `ping`. The result is `"message": "pong"`.

2. **Find a household (30 s).** Run `find_household` with name `Linh Tran`, suburb `Blacktown`.
   - Result: **HH-0001 · Linh Tran · Blacktown · 0.95**, matched on name and suburb.
   - *Say:* "A volunteer writes 'visited Linh Tran in Blacktown'. The agent gets one strong
     candidate, with a score and the reason."

3. **Same family name, different suburb (30 s).** Name `Tran`, suburb `Mount Druitt`.
   - Results: **HH-0002 Thanh Tran 0.95**, then **HH-0001 Linh Tran 0.75**.
   - *Say:* "Two Tran families. The suburb decides which comes first, and the other is
     still offered with a lower score, so a person can check."

4. **Misspelt notes (30 s).** Name `Katherine Smith`, suburb `Paramatta`. Both words are
   misspelt.
   - Results: **HH-0003 Catherine Smith 0.92** and **HH-0004 Katherine Smyth 0.91**.
   - *Say:* "Notes are messy. Both near-matches come back, nearly tied: the agent should
     ask rather than guess."

5. **A phone number settles it (30 s).** Same as step 4, plus phone `+61 2 5550 0103`.
   - Results: **HH-0003 Catherine Smith 1.00** (matched on phone), then **HH-0004 0.95**.
   - *Say:* "An exact phone number is the only thing that scores a certain 1.0."

6. **Nothing made up, nothing leaked (20 s).** Name `Zebedee Quinn`, suburb `Parramatta`.
   - Result: an empty list.
   - *Say:* "No match means no match. And notice that no result included a phone number or
     address: tools return only what the step needs."

## If something goes wrong

| Symptom | Fix |
|---|---|
| Server stops with "Schema validation: missing table" | `db/setup.sql` hasn't been run against `rootstock_app` |
| Server stops with `VINNIES_DB_URL` unresolved | `.env` not loaded: run `set -a && source .env && set +a` |
| Scores or refs differ from this script | Run `./demo-data.sh reset` (dates are relative to today; refs and scores are fixed) |
| Inspector cannot connect | Transport must be *Streamable HTTP*, and the URL must end in `/mcp` |

Not in this demo: authentication and scopes (Iteration 2), and Langfuse tracing (from
Iteration 3, when Rootstock calls these tools).
