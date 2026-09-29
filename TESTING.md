# Testing RootStock

How to run the automated suites and manually exercise the app end to end,
including the knowledge base / RAG feature. See [README.md](README.md) for
setup, layout, and configuration reference.

## 1. Automated tests

```bash
cd backend  && ./mvnw test          # unit + slice + integration tests
cd frontend && npm run build && npm run lint
```

- Controller slice tests (`*ControllerTest`) need neither Docker nor AWS. They
  run with the security filter chain switched off (`addFilters = false`): it
  isn't in a `@WebMvcTest` context, and `@PreAuthorize` sits on the services
  they mock, so there'd be nothing to enforce.
- Integration tests (`RootStockApplicationTests`, `CustomerRepositoryTest`,
  `RagIngestionIntegrationTest`, `RagQueryIntegrationTest`,
  `RagAccessControlIntegrationTest`) run against a
  Testcontainers Postgres (app bookkeeping only — no vector data lives there;
  that's Bedrock's Aurora) and **skip automatically** when Docker isn't
  running.
- `BedrockKnowledgeBaseClient` is mocked in every RAG integration test — there's
  no local/Testcontainers stand-in for a real Bedrock Knowledge Base, so these
  cover the document/version/job bookkeeping and the S3-sidecar contract, not
  Bedrock itself.
- `RagQueryIntegrationTest` also stubs the chat model, so the full
  grounded-answer path is covered without live AWS credentials.
- `RagAccessControlIntegrationTest` covers the permission model: the 401s, the
  role boundaries, the `access_groups` attribute reaching the S3 sidecar, and
  the per-caller `RetrievalFilter`. Authentication is simulated with
  `TestTokens`, which mints the same claims a real Cognito ID token carries, so
  requests take the same path through the filter chain without a network call.
  The group boundary itself is enforced inside Bedrock, which is mocked — see
  §4 for the live pass.

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

# Sign in first -- every /api/** route except login, refresh and /api/health
# needs a Cognito ID token. Seed a user with `aws cognito-idp admin-create-user`
# + `admin-set-user-password`, setting custom:tenant_id and custom:role.
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@rootstock.local","password":"..."}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["idToken"])')
# A bash array, so the space inside the header value survives word splitting.
H=(-H "Authorization: Bearer $TOKEN")

# who does the backend think you are? (tenant/role/groups all come off the token)
curl -s "${H[@]}" http://localhost:8080/api/auth/me

# upload a document
printf 'The warranty period is 24 months from the purchase date.\n' > /tmp/kb.txt
curl -s "${H[@]}" -F 'file=@/tmp/kb.txt;type=text/plain' $B/documents

# watch ingestion: QUEUED -> RUNNING -> SUCCEEDED (each attempt triggers a real
# Bedrock data-source sync and polls it, so this can take a several seconds)
curl -s "${H[@]}" "$B/jobs" | python3 -m json.tool

# the version shows INDEXED once ingestion finishes
curl -s "${H[@]}" "$B/documents" | python3 -m json.tool

# a 'default' profile is auto-seeded on first use
curl -s "${H[@]}" "$B/profiles" | python3 -m json.tool

# query
curl -s "${H[@]}" -H 'Content-Type: application/json' \
  -d '{"question":"how long is the warranty?","similarityThreshold":0}' $B/query
```

**Versioning & rollback**: re-`POST` the same file to create a new version, then
`POST /documents/{id}/versions/{n}/activate` to switch which one serves queries.

**Tenant isolation**: sign in as a user whose `custom:tenant_id` is a different
tenant and confirm each only ever sees its own documents in `GET /documents` and
its own citations in query responses — enforced by the `tenant_id` tag on every
uploaded object's S3 metadata sidecar, filtered on at query time via Bedrock's
`Retrieve` filter. The tenant can no longer be asserted by the caller at all:
it comes from the signed token, so there is no header to change.

**Roles**: with a `VIEWER` token, `GET /documents` and `POST /query` return 200
while `POST /documents`, `POST /profiles` and `POST /access-groups` return 403.
With no token (or a malformed one) every route above returns 401, and
`GET /api/health` still returns 200.

**Document access groups** (needs two users in the same tenant, one in a
Cognito group, one not):

```bash
# as ADMIN: create the group and restrict a document to it
curl -s "${H[@]}" -H 'Content-Type: application/json' -d '{"name":"hr-only"}' $B/access-groups
curl -s -X PUT "${H[@]}" -H 'Content-Type: application/json' \
  -d '{"groups":["hr-only"]}' $B/documents/$DOC_ID/access-groups
```

That queues a re-sync; wait for the version to go back to `INDEXED` before
testing, since Bedrock enforces its own copy of the grants and the previous
ones apply until the sync lands. Then ask the same question three ways: a
member of `hr-only` gets a grounded answer, a non-member gets
`grounded: false` with no citations even when naming the document explicitly
and asking for its contents verbatim, and an `ADMIN` sees it regardless of
membership.

> Because Bedrock reads the grants from the S3 sidecar, a document whose
> `blob_key` predates the single-object key scheme
> (`rag-kb/<tenant>/<doc>/v<n>`) has its rewritten sidecar land outside the data
> source's inclusion prefix — the ACL change reports success but never reaches
> Bedrock. Such a document stays admin-only (fail-closed). Re-upload it to move
> it onto the current scheme.

**Reranking**: `POST /profiles/{id}/versions` with
`{"rerankerEnabled": true, "rerankerModel": "cohere.rerank-v3-5:0"}`, then
`POST /profiles/{id}/activate`. Compare citation order/scores for the same
question against a profile with reranking off vs on — with only one
relevant document they'll look identical (nothing to reorder); a real test
needs multiple documents where the literal keyword match and the true
semantic answer are different documents, so a reordering is actually
observable.

## 5. Manual UI test — `http://localhost:5173/knowledge`

- Sign in with a seeded Cognito user. The header then shows your email, role,
  tenant and groups; there is no tenant field to type into any more.
- As a `VIEWER`, confirm the upload dropzone and the per-version actions are
  gone, and that the **Tuning** and **Access** tabs aren't offered.
- As an `ADMIN`, create a group on the **Access** tab, then expand a document
  on **Documents** and tag it — *Save access & re-sync* queues the sync.
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
- `RagProfile.chatModelId` and `.maxContextTokens` are stored, returned by the
  API, and editable in the Tuning tab, but nothing reads them at query time
  yet — don't write a test asserting they change query behavior, they don't
  (see README's Roadmap).
