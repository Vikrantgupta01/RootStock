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
  `RagIngestionIntegrationTest`, `RagQueryIntegrationTest`) run against a
  Testcontainers Postgres (app bookkeeping only — no vector data lives there;
  that's Bedrock's Aurora) and **skip automatically** when Docker isn't
  running.
- `BedrockKnowledgeBaseClient` is mocked in both RAG integration tests — there's
  no local/Testcontainers stand-in for a real Bedrock Knowledge Base, so these
  cover the document/version/job bookkeeping and the S3-sidecar contract, not
  Bedrock itself.
- `RagQueryIntegrationTest` also stubs the chat model, so the full
  grounded-answer path is covered without live AWS credentials.

## 2. One-time AWS setup (required — there's no offline mode)

Unlike earlier phases of this project, **there is no fake/offline mode for
RAG anymore** — Bedrock Knowledge Base ingestion and retrieval require real
AWS. Before running the stack for real, you need:

1. A Bedrock Knowledge Base with an Aurora PostgreSQL Serverless v2 vector
   store (the console's "Quick create" flow for this is the easiest path).
2. An S3 bucket configured as that Knowledge Base's data source.
3. Model access (in the Bedrock console's model catalog) for whatever chat
   model you configure via `BEDROCK_MODEL` — check its lifecycle status is
   `ACTIVE`, not `LEGACY`, and that it isn't gated behind an AWS Sales
   entitlement you don't have.
4. If you want reranking: the KB's own execution role needs an inline policy
   granting `bedrock:Rerank` (Resource `*`) and `bedrock:InvokeModel` scoped to
   the reranker's foundation-model ARN — reranking runs as an
   internally-assumed session off the *Knowledge Base's* role, not the calling
   application's identity, so granting this to the app's own IAM user does
   nothing. Third-party reranking models (e.g. Cohere Rerank) additionally
   require accepting the AWS Marketplace listing once, through the console, as
   a human — this can't be done by an automated role.
5. A scoped IAM identity for the running application itself (never
   root/admin) with narrowly: `bedrock:Retrieve`/`RetrieveAndGenerate`,
   `bedrock:StartIngestionJob`/`GetIngestionJob`/`ListIngestionJobs` (the last
   one is needed even though it looks unrelated — the client falls back to it
   when two uploads race and hit a sync conflict), and S3
   read/write/delete/list on the data source bucket.

Put the resulting values in a gitignored `backend/.env` (auto-loaded if you
`set -a && source .env && set +a` before running):

```bash
AWS_PROFILE=<the-scoped-identity-from-step-5>
AWS_REGION=us-east-1
RAG_BEDROCK_KB_ID=<knowledge-base-id>
RAG_BEDROCK_DATA_SOURCE_ID=<data-source-id>
RAG_S3_BUCKET=<the-data-source-bucket>
BEDROCK_MODEL=<an-active-chat-model-id>
# Only if the app's own DB lives on Aurora too rather than local Postgres:
DB_URL=jdbc:postgresql://<host>:5432/<db>
DB_USERNAME=<user>
DB_PASSWORD=<password>
SPRING_DOCKER_COMPOSE_ENABLED=false   # only needed if DB_URL points off-box --
                                       # Spring Boot's docker-compose support
                                       # otherwise silently overrides it with
                                       # the local compose.yaml Postgres
```

## 3. Run the stack

```bash
# terminal 1 — backend
cd backend
set -a && source .env && set +a
./mvnw spring-boot:run          # http://localhost:8080

# terminal 2 — frontend
cd frontend && npm install && npm run dev     # http://localhost:5173
```

If `SPRING_DOCKER_COMPOSE_ENABLED` isn't set to `false` and `backend/compose.yaml`
exists with Docker running, Spring Boot's docker-compose auto-configuration
will silently connect to that local Postgres instead of whatever `DB_URL`
says — check the startup log's `Flyway ... Database:` line to see which one
actually got used.

## 4. Manual API test

```bash
B=http://localhost:8080/api/rag
H='-H X-Tenant-Id:demo'

# upload a document
printf 'The warranty period is 24 months from the purchase date.\n' > /tmp/kb.txt
curl -s $H -F 'file=@/tmp/kb.txt;type=text/plain' $B/documents

# watch ingestion: QUEUED -> RUNNING -> SUCCEEDED (each attempt triggers a real
# Bedrock data-source sync and polls it, so this can take a several seconds)
curl -s $H "$B/jobs" | python3 -m json.tool

# the version shows INDEXED once ingestion finishes
curl -s $H "$B/documents" | python3 -m json.tool

# a 'default' profile is auto-seeded on first use
curl -s $H "$B/profiles" | python3 -m json.tool

# query
curl -s $H -H 'Content-Type: application/json' \
  -d '{"question":"how long is the warranty?","similarityThreshold":0}' $B/query
```

**Versioning & rollback**: re-`POST` the same file to create a new version, then
`POST /documents/{id}/versions/{n}/activate` to switch which one serves queries.

**Tenant isolation**: repeat the upload/query with a different `X-Tenant-Id`
value and confirm each tenant only ever sees its own documents in
`GET /documents` and its own citations in query responses — this is enforced
by the `tenant_id` tag on every uploaded object's S3 metadata sidecar, filtered
on at query time via Bedrock's `Retrieve` filter.

**Reranking**: `POST /profiles/{id}/versions` with
`{"rerankerEnabled": true, "rerankerModel": "cohere.rerank-v3-5:0"}`, then
`POST /profiles/{id}/activate`. Compare citation order/scores for the same
question against a profile with reranking off vs on — with only one
relevant document they'll look identical (nothing to reorder); a real test
needs multiple documents where the literal keyword match and the true
semantic answer are different documents, so a reordering is actually
observable.

## 5. Manual UI test — `http://localhost:5173/knowledge`

- Set the **Tenant** field (e.g. `demo`) — every request carries it as
  `X-Tenant-Id`.
- **Documents** — drop a PDF/Word/text file, watch the upload progress bar,
  then expand the row to see the version, activate / reindex / download /
  delete.
- **Activity** — the ingestion job appears and flips to `succeeded` once the
  Bedrock sync completes.
- **Tuning** — change top-k or the reranker toggle → *Save & activate*
  (flips immediately — there's no re-index to wait for anymore).
- **Playground** — ask a question → grounded answer with citation chips.

## Known gaps in coverage

- No automated test exercises real AWS end-to-end (by design — CI shouldn't
  depend on live cloud resources). The manual walkthroughs above are
  currently the only way to validate the real Bedrock/Aurora/S3 wiring.
- `RagProfile.chatModelId` and `.maxContextTokens` are stored and returned by
  the API but nothing reads them — don't write a test asserting they change
  query behavior, they don't (see README's Known limitations).
