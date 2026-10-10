# Demo 4: an ontology you can see

**Story (about 3 minutes):** before any agent reads a visit note, Rootstock has to know
what a Vinnies case *is*: a household, its needs, the help given, and the words members
actually use for them. That knowledge lives in one file, the Vinnies ontology, built on
Rootstock's core concepts. Everything an agent needs, its output schema and its glossary,
is generated from that file, and a mistake in it is caught with a clear message. All data
is fictional.

## Before the demo (about 2 minutes)

```bash
# Rootstock (port 8080). Its .env must have
#   ROOTSTOCK_PACKS_PATHS=<repo>/vinnies/vinnies-pack/packs
cd rootstock-core
set -a && source .env && set +a && mvn spring-boot:run
#    The log shows: Pack 'vinnies' at ...: ontology vinnies 1.0.0 valid
#    (9 concepts, 3 vocabularies, 2 projections)

# The demo UI (port 5173)
cd frontend && npm run dev
```

Sign in at http://localhost:5173 as an **admin** (Reload packs is admin-only) and open
**Ontology**. Have `vinnies/vinnies-pack/packs/vinnies/ontology.yaml` open in an editor.

## Script

1. **Rootstock found the pack (15 s).** The **vinnies** tab is marked **valid**.
   - *Say:* "Rootstock doesn't know about Vinnies. It reads whatever packs it's pointed at.
     This one is the Vinnies pack: Sinew's configuration for this client."

2. **The concepts (40 s).** On **Concepts**, look at **Case** and **Household**.
   - Result: Case *extends core.Case*; it has an urgency, risk flags, a visit date, and
     relations to the Household, its Needs, the Assistance and Referrals, and the visiting
     Member. Household's `contact` is marked **pii**.
   - *Say:* "Every client's case is built on the same core concepts: a party, a case, a
     request, an action. That's what lets Rootstock's generic steps work for Vinnies, or
     for an insurance broker. Personal data is flagged here, once, and that flag will drive
     masking in the traces."

3. **The words people use (30 s).** Open **Vocabularies**.
   - Result: NeedCategory FOOD, ENERGY_BILL and RENT, each with a definition and synonyms
     ("power bill", "behind on rent"); Urgency; RiskFlag.
   - *Say:* "Visit notes are messy. A member writes 'electricity cut off'; the record needs
     ENERGY_BILL. The synonyms are how the model gets from one to the other."

4. **The rule (15 s).** Open **Constraints**: `urgent-needs-follow-up`, *when Case.riskFlags
   is notEmpty then Case.urgency is HIGH*.

5. **What the agent will get (40 s).** Open **Projections**, then **case-extraction**.
   - Result: on the left the JSON Schema the extraction agent's answer must match: urgency
     as an enum with its definitions, at least one need, the household embedded, the
     visiting member referenced by id. On the right the glossary that goes into its prompt.
   - Then click **chase-context**: a much smaller schema, just urgency, need categories and
     a household id.
   - *Say:* "Nobody wrote this schema. It's generated from the ontology, so a new need
     category shows up everywhere at once. And each agent sees only the fields it needs:
     the chase writer never sees the household's contact details."

6. **A mistake, caught (40 s).** In the editor, change Need's `category` vocabulary from
   `NeedCategory` to `NeedCategories` and save. Click **Reload packs**.
   - Result: the vinnies tab turns **invalid**, with the problem and where it is:
     `entities.Need.attributes.category.vocab: unknown vocabulary 'NeedCategories'; known:
     [NeedCategory, Urgency, RiskFlag, core.IssueSeverity, core.ApprovalDecision]`.
   - *Say:* "A typo in the ontology doesn't reach an agent and doesn't crash Rootstock.
     The pack is set aside with a message that says what's wrong and where."
   - Undo the change, save, **Reload packs**: valid again.

## After the demo

Make sure `ontology.yaml` is back to its committed state (`git diff` shows nothing), then
Reload packs once more.
