# Samples for the consistency judge

Each file is visit notes (`input`) and a record extracted from them (`record`), some
with a planted mistake. Rootstock's `live` test `PackSamplesLiveIT` runs the
`consistency-judge` on them.

- `expect.flagged`: record paths the judge must report (an issue whose path starts
  with each one).
- `expect.clean: true`: the judge must report nothing.

All names and data are fictional.
