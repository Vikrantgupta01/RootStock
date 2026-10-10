# Sample visit notes for the case extractor

Fictional notes, each with what extracting it must produce. Rootstock's `live`
test `PackSamplesLiveIT` runs the `case-extractor` agent on every file here
against the real model and checks the result.

- `input`: the notes, as a member would submit them.
- `expect`: field path to value. Paths go through lists (`needs.category` is every
  need's category). A list value means exactly that set of values; a single value
  means that value only.
- `missing`: exactly the required fields the notes do not give, which validate
  turns into clarify questions.

All names, places and numbers are made up.
