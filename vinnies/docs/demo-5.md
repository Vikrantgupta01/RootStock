# Demo 5: a graph that runs

**Story (about 3 minutes):** a Vinnies case is a process, not a single AI call: read the
notes, look things up, check them, ask the member if something's missing, draft help, and
let a coordinator approve before anything is written. That process is defined in Vinnies'
pack as a graph, and Rootstock runs it. Each step lights up as it runs, the run stops
where a person is needed, and every step is one span in a single Langfuse trace. The steps
are stand-ins for now: the flow is real, the AI work arrives next. All data is fictional.

## Before the demo (about 2 minutes)

```bash
# Rootstock (port 8080). Its .env must have
#   ROOTSTOCK_PACKS_PATHS=<repo>/vinnies/vinnies-pack/packs
#   ROOTSTOCK_TOOLS_FILE=<repo>/vinnies/vinnies-pack/packs/vinnies/tools.yaml
cd rootstock-core
set -a && source .env && set +a && mvn spring-boot:run
#    The log shows: Graph 'case-intake' 1.0.0 of pack 'vinnies': 10 nodes, 6 agents;
#    pauses before [review, await_input], after [clarify]

# The demo UI (port 5173)
cd frontend && npm run dev
```

Sign in at http://localhost:5173 and open **Cases**. Open Langfuse in a second tab. Have
`vinnies/vinnies-pack/packs/vinnies/graph.yaml` open in an editor.

## Script

1. **The process is configuration (30 s).** Show `graph.yaml` next to the Cases screen.
   - Result: the screen lists the same ten nodes and the routes out of validate: clarify
     if the member can answer, chase if only the household can, otherwise draft.
   - *Say:* "This is Vinnies' process, written as configuration. Rootstock provides the
     building blocks; the pack only arranges them."

2. **Run a case (40 s).** Leave the sample notes, Simulate **Nothing missing**, click
   **Submit case**.
   - Result: ingest, extract, enrich, validate and draft light up one after another; the run
     stops **paused before review**. The log shows each step and how long it took; the
     audit shows what each step would do.
   - *Say:* "It stops before review on purpose. Nothing is ever written to Vinnies' system
     without a coordinator's approval: Rootstock refuses to start a graph that allows it."

3. **One trace (30 s).** Click **Langfuse trace**.
   - Result: one `process-case` trace with a span per node, the case id as its session.
   - *Say:* "When the real AI steps arrive, each model call and tool call will sit under its
     step here."

4. **The member can fill the gap (30 s).** Simulate **A detail the member can give**,
   submit.
   - Result: validate routes to **clarify**, and the run stops **paused after clarify**,
     waiting for the member's answer.

5. **Only the household can (20 s).** Simulate **A detail only the household can give**,
   submit.
   - Result: validate routes to **chase**, which drafts a CHASE_MESSAGE; the run stops before
     review, because even a message to a household needs approval.

6. **An unsafe graph doesn't start (30 s, optional, in a terminal).** In a scratch copy of
   the pack, change `{ from: draft, to: review }` to `{ from: draft, to: commit }` and start
   Rootstock against it.
   - Result: startup fails with `graph.yaml node commit: can be reached from START without
     passing a human-review node in runtime.interruptBefore; every write needs a human
     approval first`.
   - *Say:* "The safety rule isn't a convention; it's checked before anything runs."

## Notes

- Runs live in memory for now: restarting Rootstock forgets them, and a paused run can't
  be resumed yet (review arrives in Iteration 9).
