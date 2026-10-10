# Demo 6: notes become a structured record

**Story (about 3 minutes):** a member's visit notes are hurried and messy. Rootstock turns
them into a case record in Vinnies' own terms (needs, urgency, risk flags), shown right
beside the notes. When the notes don't say something the case needs, such as the visit
date, Rootstock doesn't guess: it asks the member. All data is fictional.

## Before the demo (about 2 minutes)

```bash
# Rootstock (port 8080). Its .env must have ROOTSTOCK_PACKS_PATHS and
# ROOTSTOCK_TOOLS_FILE pointing at vinnies/vinnies-pack/packs (see ../README.md),
# Bedrock access for BEDROCK_MODEL, and the Langfuse keys.
cd rootstock-core
set -a && source .env && set +a && mvn spring-boot:run
#    The log shows: Model profiles: [extraction=default model, fast=…, drafting=…]
#    and: Pack 'vinnies' bundles prompts [vinnies/extract-case]

cd frontend && npm run dev
```

Sign in at http://localhost:5173 and open **Cases**. Open Langfuse in a second tab. Have
the sample notes in `vinnies/vinnies-pack/packs/vinnies/samples/case-extractor/` to hand.

## Script

1. **Messy notes in, a record out (60 s).** Leave the sample notes on the Cases screen and
   click **Submit case**.
   - Result: extract takes a few seconds (a real model call), then the run goes on to
     review. **Notes as submitted** sits beside **Extracted record**: urgency, risk flags
     (the AGL final notice is a DISCONNECTION), the needs, the $80 voucher as assistance,
     whether consent was given.
   - Hover a code: its definition comes from Vinnies' ontology.
   - *Say:* "'Final notice from AGL' became DISCONNECTION. That's Vinnies' own category,
     defined once in their ontology with its synonyms. The model is given those; it
     doesn't make up its own."

2. **It doesn't guess (40 s).** Paste `02-eviction-no-date.yaml`'s notes (no visit date)
   and submit.
   - Result: the record shows **Visit date: not in the notes**, highlighted; the issues
     list "Missing visitDate" as BLOCKING, answerable by the SUBMITTER; the run goes to
     **clarify** and stops, waiting for the member.
   - *Say:* "The schema the model fills lets it say 'I don't know'. A missing date becomes
     a question for the member, never an invented date."

3. **Every step is traced (40 s).** Click **Langfuse trace**.
   - Result: the extract span holds the model call: the prompt with the glossary and
     schema, the JSON reply, tokens and cost. The generation names the prompt it used
     (`vinnies/extract-case`; linked to its version once the prompt lives in Langfuse).
   - *Say:* "If quality changes, we can see which prompt version and which model did it."

4. **Prompts change without a release (20 s, optional).** Show
   `packs/vinnies/prompts/extract-case.yaml`.
   - *Say:* "The live prompt is managed in Langfuse by name and label. This copy is the
     fallback, so a Langfuse outage never stops a case."

## If something goes wrong

- **Run FAILED, "No chat backend" or "AI provider failed"**: Bedrock credentials or model
  access. Check `/status`.
- **Run PARKED**: the model's reply never matched the schema after its retries. The
  reason lists what was wrong; the trace shows each attempt.
- **Log warns "Langfuse has no prompt 'vinnies/extract-case'"**: expected until the prompt
  is published; the bundled copy is used.
