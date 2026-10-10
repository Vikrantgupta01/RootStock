# Samples for the context enricher

Each file is the state the enricher starts from (`input`: the visit notes, `record`: the
case record as extracted) and what its lookups must show. Rootstock's `live` test
`PackSamplesLiveIT` runs the `context-enricher` against the real model and the running
Vinnies MCP server (its demo data).

- `expect.ok`: tools that must have at least one successful lookup.
- `expect.notOk`: tools that must not have one (e.g. no history when no household matched).
- `expect.arguments`: `tool.argument` to the value (or set of values) its successful
  lookups were called with.

All names and data are fictional.
