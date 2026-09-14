# Testing RootStock

How to run the automated suites and manually exercise the app end to end,
including the knowledge base / RAG feature. See [README.md](README.md) for
setup, layout, and configuration reference.

## 1. Automated tests

```bash
cd backend  && ./mvnw test          # unit + slice + integration tests
cd frontend && npm run build && npm run lint
```

- Controller slice tests (`*ControllerTest`) need neither Docker nor AWS.
- Integration tests (`RootStockApplicationTests`, `CustomerRepositoryTest`,
  `RagIngestionIntegrationTest`, `RagQueryIntegrationTest`,
  `RagProfileActivationIntegrationTest`) run against a Testcontainers Postgres
  and **skip automatically** when Docker isn't running.
- `RagQueryIntegrationTest` stubs the chat model, so the full grounded-answer
  path is covered without AWS credentials.

## 2. Run the stack

```bash
# terminal 1 — backend (auto-starts the pgvector container via compose.yaml)
cd backend && ./mvnw spring-boot:run          # http://localhost:8080

# terminal 2 — frontend
cd frontend && npm install && npm run dev     # http://localhost:5173
```

## 3. Manual API test — no AWS needed

Retrieval uses the offline, deterministic `fake` embedder by default
(`RAG_EMBEDDING_MODE`), so the whole document/profile pipeline works without any
AWS setup. Only the final chat answer needs Bedrock (§5).

```bash
B=http://localhost:8080/api/rag
H='-H X-Tenant-Id:demo'

# upload a document
printf 'The warranty period is 24 months from the purchase date.\n' > /tmp/kb.txt
curl -s $H -F 'file=@/tmp/kb.txt;type=text/plain' $B/documents

# watch ingestion: QUEUED -> SUCCEEDED
curl -s $H "$B/jobs" | python3 -m json.tool

# the version shows INDEXED once ingestion finishes
curl -s $H "$B/documents" | python3 -m json.tool

# a 'default' profile is auto-seeded on first use
curl -s $H "$B/profiles" | python3 -m json.tool

# query — retrieval works standalone; the answer step needs Bedrock (see §5)
curl -s $H -H 'Content-Type: application/json' \
  -d '{"question":"how long is the warranty?","similarityThreshold":0}' $B/query
```

**Versioning & rollback**: re-`POST` the same file to create a new version, then
`POST /documents/{id}/versions/{n}/activate` to switch which one serves queries.

**Blue/green re-index**: edit a profile's `chunkSize` via
`POST /profiles/{id}/versions`, then `POST /profiles/{id}/activate`. The response
shows `state: PENDING` while the previous profile keeps serving; it flips
automatically once the re-index job(s) succeed —
poll `GET /profiles/activations/{activationId}`.

## 4. Manual UI test — `http://localhost:5173/knowledge`

- Set the **Tenant** field (e.g. `demo`) — every request carries it as
  `X-Tenant-Id`.
- **Documents** — drop a PDF/Word/text file, watch the upload progress bar,
  expand the row to see the version, then activate / reindex / download / delete.
- **Activity** — the ingestion job appears and flips to `succeeded`.
- **Tuning** — change top-k → *Save & activate* (flips immediately). Change
  chunk size → *Save & activate* → a blue/green banner shows until the
  re-index finishes.
- **Playground** — ask a question → grounded answer with citation chips
  (retrieval/citations work without AWS; the answer text needs §5).

## 5. Exercising the LLM answer (optional, needs AWS)

`/api/chat` and the RAG query's answer step call AWS Bedrock. Grant your account
access to a model (Bedrock console → *Model access*), then set:

```bash
export AWS_REGION=us-east-1
export BEDROCK_MODEL=us.anthropic.claude-3-7-sonnet-20250219-v1:0   # a model you're granted
```

and restart the backend. Without this, chat/query return
`503 AI backend unavailable` — everything else (upload, ingestion, profiles,
retrieval, citations) works regardless.
