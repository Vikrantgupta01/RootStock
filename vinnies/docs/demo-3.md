# Demo 3: Rootstock calls the Vinnies tools

**Story (about 3 minutes):** Rootstock, the engine, now reaches Vinnies' system the way it
will during a real case: as a machine with its own identity, and only through one gate that
knows which step may call which tool. Every call, allowed or refused, is recorded in
Langfuse. All data is fictional.

## Before the demo (about 3 minutes)

Three processes, each in its own terminal:

```bash
# 1. The Vinnies system (port 8081)
cd vinnies/vinnies-mcp-server
./demo-data.sh reset
set -a && source .env && set +a && mvn spring-boot:run

# 2. Rootstock (port 8080). Its .env must have ROOTSTOCK_TOOLS_FILE pointing at
#    vinnies/vinnies-pack/packs/vinnies/tools.yaml
cd rootstock-core
set -a && source .env && set +a && mvn spring-boot:run
#    The log shows the allowlists, then:
#    MCP connection 'vinnies' (http://localhost:8081/mcp): 5 tools [...]

# 3. The demo UI (port 5173)
cd frontend && npm run dev
```

Sign in at http://localhost:5173 as an **admin** and open **Tools**. Open Langfuse in a
second browser tab.

## Script

1. **Rootstock knows Vinnies' tools (20 s).** On the Tools page, open the **Tool** list.
   - Result: the five tools, each with Vinnies' own description and input form.
   - *Say:* "Rootstock asked Vinnies' system what it offers, using its own machine identity.
     Nothing about Vinnies is built into Rootstock: it's configuration in the Vinnies pack."

2. **A step and its permissions (20 s).** Set **Node** to `enrich`.
   - *Say:* "A case moves through steps. *Enrich* looks things up, so it may use the four read
     tools, and nothing else. Writing only ever happens in *commit*, after a person approves."

3. **An allowed call (40 s).** Tool `find_household`, name `Linh Tran`, suburb `Blacktown`,
   case id `case-42` → **Run**.
   - Result: **OK**, **HH-0001 · 0.95**, and a **Langfuse trace** link.
   - Open the link: trace `explore-tool`, session **case-42**, with a `tool-find_household`
     span showing the input, the output, the node, the acting user and the time taken.

4. **A refused call (40 s).** Same node and case id, tool **`ping`** (marked *not allowed*) →
   **Run (expect refusal)**.
   - Result: **BLOCKED**: "Tool 'ping' is not allowed in node 'enrich'."
   - In Langfuse, session **case-42** now has a second trace; its `tool-ping` span is a
     **WARNING**, with the reason, and took **0 ms**: it never reached Vinnies.
   - *Say:* "The rules live in configuration, not in the AI. Even if text in someone's notes
     told the AI to do something else, the gate would refuse it, and we'd see the attempt."

5. **One case, one story (20 s).** In Langfuse, open **Sessions → case-42**.
   - Result: both calls together, in order.
   - *Say:* "Everything done for a case is in one place: what was asked, what was allowed,
     what was refused, and who it was for."

## If something goes wrong

| Symptom | Fix |
|---|---|
| No **Tools** link in the top bar | Sign in as an admin: the explorer shows client records |
| "No client-system tools are configured" | Set `ROOTSTOCK_TOOLS_FILE` in `rootstock-core/.env` and restart Rootstock |
| Tools listed as *Unavailable* | Start the Vinnies server first; Rootstock reconnects on its own |
| Rootstock log warns "is not available yet" | Same: Vinnies wasn't up when Rootstock started. It's harmless, and the next call connects |
| No Langfuse link on a result | Langfuse keys are not set in `rootstock-core/.env`; the call still works |
| Trace not in Langfuse yet | Spans are sent in batches; wait a few seconds and refresh |

Not in this demo: the workflow graph itself (Iteration 5). The explorer stands in for a
step so the gate can be shown before the steps exist.
