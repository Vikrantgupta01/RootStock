# Demo 7: enrich with live client data

**Story (about 3 minutes):** the record from the notes is only half the picture. Rootstock
now looks the household up in Vinnies' own system: who they are, what help they've had
this year, the guideline for each need and the local services that could help. It can
only use the read tools Vinnies' configuration allows at that step, and every lookup is
on screen and in the trace. All data is fictional.

## Before the demo (about 3 minutes)

```bash
# The Vinnies system (port 8081), with its demo data loaded (see ../README.md)
cd vinnies/vinnies-mcp-server && set -a && source .env && set +a && mvn spring-boot:run

# Rootstock (port 8080), .env pointing at the Vinnies pack and tools file
cd rootstock-core && set -a && source .env && set +a && mvn spring-boot:run
#    The log shows: Pack 'vinnies' bundles prompts [vinnies/enrich, vinnies/extract-case]

cd frontend && npm run dev
```

Sign in at http://localhost:5173 and open **Cases**; Langfuse in a second tab; have
`vinnies/vinnies-pack/packs/vinnies/agents/context-enricher.yaml` open in an editor.

## Script

1. **A known household (70 s).** Leave the sample notes (Linh Tran, Blacktown) and submit.
   - Result: after extract, **enrich** runs for a few seconds. Under the record,
     **Context from the client system** shows a short summary (the household matched,
     its recent assistance, the limits that apply), then each lookup: the guideline and
     local services for each need (from the pack's plan), then `find_household` and
     `get_assistance_history` (asked for by the model).
   - Open `find_household`: candidates with match scores; the history uses the matched
     household's reference.
   - *Say:* "The fixed lookups are configuration: the guideline for each need, services
     near the household. Finding the right household from a name in the notes needs
     judgement, so the model does that part, and it says so when nothing matches."

2. **Only what's allowed (40 s).** Show `context-enricher.yaml` and `tools.yaml`.
   - *Say:* "The model is offered four read tools, the ones this step is allowed. A write
     tool isn't even shown to it; if it asked for one, Rootstock would refuse before
     anything reached Vinnies. That's tested."

3. **An unknown household (40 s).** Submit the notes from
   `samples/context-enricher/02-unknown-household.yaml`.
   - Result: `find_household` returns no clear match; the summary says no household
     matched and why; there is no history lookup.
   - *Say:* "It doesn't pick the nearest name. A wrong household would mean the wrong
     history and the wrong limits."

4. **One trace (30 s).** Open the Langfuse trace.
   - Result: under the enrich span, each model call (Claude Haiku, the `fast` profile) and
     each tool call, with its arguments and the Vinnies answer.

## If something goes wrong

- **Every lookup UNAVAILABLE**: the Vinnies server is down or its database pool went
  stale (e.g. after the laptop slept). Restart it; the case still runs, with the failed
  lookups shown.
- **Run FAILED with a Bedrock 403 on Haiku**: the app's IAM user lacks access to the
  `fast` model; set `LLM_FAST_MODEL` to the default model or grant access.
