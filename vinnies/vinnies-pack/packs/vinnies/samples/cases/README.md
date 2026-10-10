# Whole cases: the Iteration 8 demo problems

Fictional visit notes, each with one problem validate must catch. Rootstock's `live` test
`CaseRunLiveIT` runs each one through the whole case-intake graph (real model, prompts and
the Vinnies MCP server with its demo data) and checks the run.

- `expect.issues`: rule ids that must be among the case's issues.
- `expect.pausedAt`: where the run must stop (`review`, `clarify`).

The households are in the Vinnies demo data except where noted.
