# Demo 9: draft and human review

**Story (about 3 minutes):** Rootstock drafts what should happen next (referrals to local
services, a follow-up) but nothing goes ahead until a coordinator says so, in Vinnies' own
app. A case can wait days: it waits as data, not as a process held open, and when the
coordinator approves it is checked again against Vinnies' data as it is that day before
anything is written. Only a coordinator can decide it. All data is fictional.

## Before the demo (about 3 minutes)

```bash
# Vinnies system (8081) and Rootstock (8080) as in demo 7.
cd vinnies/vinnies-frontend && npm install && npm run dev     # http://localhost:5174
cd frontend && npm run dev                                    # Rootstock UI, http://localhost:5173
```

Demo users (fictional, in the Cognito pool): `volunteer@rootstock.local` (no group) and
`coordinator@rootstock.local` (group `coordinator`); passwords are kept outside the repo.

## Script

1. **A volunteer submits notes (40 s).** In the Rootstock UI, sign in as the volunteer and
   submit the sample notes on **Cases** (Linh Tran, Blacktown).
   - Result: extract, enrich, validate and **draft** run, and the run ends; the case shows
     **waiting for coordinator**. The draft: referrals to local services found in Vinnies' directory, and
     a follow-up timed by urgency.

2. **The coordinator's queue (30 s).** In the Vinnies app (5174), sign in as the
   coordinator.
   - Result: "1 case waiting for review", with urgency, suburb and needs.
   - *Say:* "This is Vinnies' own app. Rootstock is the engine behind it: it holds the case,
     its checks and its draft, and tells the app what's waiting."

3. **Restart mid-review (30 s, optional).** Stop and start Rootstock, refresh the Vinnies app.
   - Result: the case is still waiting. *Say:* "A case can wait days for a coordinator.
     Nothing is held open meanwhile: the case is saved, and the decision starts a fresh,
     short run."

4. **Review (60 s).** Open the case: what the checks found (the repeat request, the judge's
   notes), the visit notes beside the record, what the Vinnies system said, the draft.
   - Click **Correct it** to show the form (built from Vinnies' ontology: choices for
     urgency and risk, dates, lists of needs); discard.
   - Add a comment and **Approve**.
   - Result: back to the queue, now empty. In the Rootstock UI the case's decision run:
     review (who approved, their comment), enrich and validate **again**, then commit (a stub
     until Iteration 10) with the approved actions; the case is **done**.
   - *Say:* "Before writing, it looked everything up again. If Vinnies' data had changed
     since the draft (another payment, a new limit), the case would be back in the queue
     with what's new, not written."

5. **Only coordinators decide (20 s).** Sign in to the Vinnies app as the volunteer.
   - Result: "Only coordinators review cases." The API refuses a volunteer's decision
     (403) whatever app sends it.

## If something goes wrong

- **The queue is empty**: the case may be paused somewhere else (clarify, for a missing
  detail) or still running; check it in the Rootstock UI.
- **409 on a decision**: someone else decided it first.
