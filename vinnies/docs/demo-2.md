# Demo 2: all read tools, secured

**Story (about 4 minutes):** the Vinnies system now answers everything the agent needs to
read, from the household and its history to the client's own guidelines and local services.
**Nothing gets in without a token from Vinnies' identity provider**, and a token only opens
what its scope allows. All data is fictional.

## Before the demo (about 2 minutes)

```bash
cd vinnies/vinnies-mcp-server
./demo-data.sh reset                      # clean, known data (log ends with guidelines=3 fingerprint=…)
set -a && source .env && set +a
mvn spring-boot:run                       # wait for "Started VinniesMcpServerApplication"
```

In a second terminal:

```bash
./get-token.sh read     # copy this token: scope vinnies/read, valid 60 minutes
./get-token.sh write    # and this one: scope vinnies/write (used in step 6)
npx @modelcontextprotocol/inspector
```

In the Inspector, set **Transport** to *Streamable HTTP* and the URL to
`http://localhost:8081/mcp`.

## Script

1. **No token, no entry (20 s).** Choose **Connect** with no Authorization header.
   - Result: the connection fails; the server answers **401**.
   - *Say:* "The client's system doesn't talk to anyone it can't identify. Not even a list of
     tools."

2. **Connect with a read token (20 s).** Add the header `Authorization: Bearer <read token>`,
   then **Connect** and **List Tools**.
   - Result: five tools: `find_household`, `get_assistance_history`,
     `get_assistance_guidelines`, `search_local_services`, `ping`.
   - *Say:* "The token comes from Cognito, using the agent's own machine identity, not a
     person's login. It says what the agent may do: read."

3. **Who is this? (30 s).** `find_household`: `Linh Tran`, `Blacktown`.
   - Result: **HH-0001 · 0.95**.

4. **What have they already had? (30 s).** `get_assistance_history`: `HH-0001`, `90` days.
   - Result: energy help $280 three weeks ago and $250 about ten weeks ago, plus food and rent.
   - *Say:* "Two energy payments inside three months."

5. **What do the client's rules say? (40 s).** `get_assistance_guidelines`: `ENERGY_BILL`.
   - Result: limit **$400 per visit**, repeat window **90 days**.
   - Then open **Resources**, read `vinnies://guidelines/ENERGY_BILL`: the same rule as a
     document, for a reviewer.
   - *Say:* "So a third energy request now is a repeat, and needs a coordinator. The rule comes
     from Vinnies' own system, and their staff can change it without us."

6. **Where can we refer them? (30 s).** `search_local_services`: `ENERGY_BILL`, `Blacktown`.
   - Result: **Blacktown Energy Bill Help Desk**, with address, phone, hours and eligibility.

7. **Scopes are enforced (30 s).** Reconnect with the **write** token instead, and run
   `find_household` again.
   - Result: an error, **"Access Denied"**: the tool never ran.
   - *Say:* "Each tool checks the token's permission. Later, the agent asks for write access
     only at the moment a coordinator approves, never before."

## If something goes wrong

| Symptom | Fix |
|---|---|
| Connect fails even with a token | The token expired (60 minutes): run `./get-token.sh read` again |
| Server stops with `VINNIES_AUTH_ISSUER_URI` unresolved | `.env` not loaded: `set -a && source .env && set +a` |
| `get-token.sh` says Cognito refused | Check `VINNIES_MCP_CLIENT_ID`, `_SECRET` and `_TOKEN_URL` in `.env` |
| Server stops with "missing table [assistance_guideline]" | Run `db/setup.sql` again (safe to rerun), then `./demo-data.sh reset` |
| Inspector starts an OAuth login page | It read the server's metadata; close it and use the Authorization header instead |

Not in this demo: Rootstock calling these tools (Iteration 3), and Langfuse tracing.
