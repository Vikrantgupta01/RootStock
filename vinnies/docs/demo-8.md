# Demo 8: validation that catches problems

**Story (about 3 minutes):** a record can look complete and still be wrong. Rootstock now
checks every case in four layers: the record's shape, the rules in Vinnies' ontology,
Vinnies' own business rules (with the limits and windows from Vinnies' system), and a
judge that compares the record with the notes. Each problem says how serious it is and
who can fix it, and that decides where the case goes next. All data is fictional.

## Before the demo (about 3 minutes)

Start the Vinnies MCP server, Rootstock and the UI as in [demo 7](demo-7.md). Open
**Cases**, Langfuse in a second tab, and `vinnies/vinnies-pack/packs/vinnies/rules.yaml`
in an editor. The four notes are in `samples/cases/`.

## Script

1. **Over the limit (40 s).** Submit `01-over-limit.yaml`'s notes ($800 rent paid).
   - Result: the run reaches **review**. **Issues** shows a warning: RENT assistance of
     $800 is over the $600 limit per visit; *the coordinator decides*. The amount is
     marked in the record.
   - *Say:* "$600 isn't in Rootstock or in the rules file: it's Vinnies' current
     guideline, looked up in their system during enrich. If they change it tomorrow,
     this check changes with it."

2. **A repeat request (40 s).** Submit `02-repeat-request.yaml` (Linh Tran's power bill).
   - Result: a warning: ENERGY_BILL help was given on a date inside the 90-day repeat
     window. Open **Context**: the history lookup shows the earlier payments.

3. **A new household without consent (40 s).** Submit `03-new-household-no-consent.yaml`.
   - Result: two **blocking** issues, contact and consent, *the household will be asked*;
     the run goes to **chase** and stops before review with a message drafted for the
     household.
   - *Say:* "Blocking problems the member can't answer go to the household, not to a
     guess."

4. **A risk that isn't urgent (40 s).** Submit `04-risk-not-urgent.yaml`.
   - Result: a blocking issue from the ontology (a case with a risk flag must be HIGH
     urgency); the run goes to **clarify** to ask the member.

5. **The judge (20 s, optional).** Point at the judgment layer in the validate audit line;
   in Langfuse, open the validate span to see the judge's model call and its answer.
   - *Say:* "A model checks the record against the notes for anything invented. Its
     findings are only ever warnings: a model's opinion never blocks a case by itself."

## If something goes wrong

- **No R02/R03/R05 issues**: enrich's lookups failed (Context shows UNAVAILABLE); restart
  the Vinnies server. Rules without their lookups use the YAML defaults (limits) or
  have nothing to check (history).
- **Step 4 shows no issue**: the model set urgency HIGH itself, which is the right
  outcome; the issue appears whenever the record has a risk flag without HIGH urgency.
