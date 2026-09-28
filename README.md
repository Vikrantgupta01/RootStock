# RootStock

AI-powered web app. Java 21 / Spring Boot backend with Spring AI wired to **AWS
Bedrock** for chat, a local PostgreSQL database for app data (documents,
profiles, jobs), and a React + TypeScript frontend.

Ships a `Customer` CRUD sample and a tenant-scoped **knowledge base / RAG**
subsystem backed by an **AWS Bedrock Knowledge Base** (Aurora PostgreSQL
Serverless v2 as the vector store) — document ingestion, tunable query
profiles, grounded query with optional reranking — see below. For how to run
and test all of it, see **[TESTING.md](TESTING.md)**.

## Layout

```
RootStock/
├── backend/     Spring Boot 4 · Java 21 · Maven · Spring AI (Bedrock Converse) · JPA · Flyway
│   └── compose.yaml   local Postgres for app data (auto-started in dev by Spring Boot)
└── frontend/    React 19 · TypeScript · Vite · React Router · TanStack Query
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | Project targets Java 21; a newer JDK on `PATH` is fine. |
| Node.js | 20+ | For the frontend. |
| Docker | any recent | Runs the local app-data Postgres; also used by the integration tests. |
| AWS account + credentials | — | Required for **both** chat and the RAG feature — there's no offline/fake mode for either anymore. Needs a Bedrock Knowledge Base (Aurora PostgreSQL vector store) already provisioned, an S3 bucket as its data source, model access for the configured chat model, and an IAM identity with the narrow set of Bedrock/S3 permissions the app needs (see `rootstock-rag-app-policy` pattern — never run this as an AWS root/admin identity). Provide credentials via the standard AWS chain (an SSO/named profile is the simplest for local dev). |

## Run it

### 1. Database

Spring Boot's docker-compose integration starts `backend/compose.yaml`
automatically when you run the backend. To manage it by hand:

```bash
docker compose -f backend/compose.yaml up -d
```

### 2. Backend

```bash
cd backend
# AWS credentials + region, and the Bedrock Knowledge Base / data source ids
# (see Configuration below), are required for both /api/chat and the RAG
# feature to actually do anything. A gitignored .env file works well for
# local dev -- see TESTING.md.
./mvnw spring-boot:run
```

- `GET  http://localhost:8080/api/health` → `{ "status": "UP", ... }`
- `GET  http://localhost:8080/actuator/health`
- `POST http://localhost:8080/api/chat` with `{ "message": "hello" }` →
  `{ "reply": "..." }` (503 with a clear message until AWS Bedrock is configured)
- `POST http://localhost:8080/api/chat` with `Accept: text/event-stream` → token stream

### 3. Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

The Vite dev server proxies `/api/*` to `http://localhost:8080`, so no CORS setup
is needed locally. For non-dev builds set `VITE_API_BASE_URL` (see `.env.example`).

## Configuration

Backend config lives in `backend/src/main/resources/application.yml`. Key knobs
(all overridable by environment variable):

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/rootstock` |
| `spring.datasource.username` / `.password` | `DB_USERNAME` / `DB_PASSWORD` | `rootstock` / `rootstock` |
| `spring.ai.bedrock.aws.region` | `AWS_REGION` | `us-east-1` |
| `spring.ai.bedrock.converse.chat.options.model` | `BEDROCK_MODEL` | `us.anthropic.claude-sonnet-4-5-20250929-v1:0` |
| `rootstock.rag.bedrock.knowledge-base-id` | `RAG_BEDROCK_KB_ID` | _(your Knowledge Base id)_ |
| `rootstock.rag.bedrock.data-source-id` | `RAG_BEDROCK_DATA_SOURCE_ID` | _(your Knowledge Base's S3 data source id)_ |
| `rootstock.rag.blob.backend` | `RAG_BLOB_BACKEND` | `s3` (real AWS S3 — required; see below) |
| `rootstock.rag.blob.s3.bucket` | `RAG_S3_BUCKET` | the S3 bucket your Knowledge Base's data source reads from |
| `rootstock.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |

> Set `BEDROCK_MODEL` to a model or inference-profile id your AWS account has
> **model access** for in the chosen region — this can change over time as
> providers deprecate models (Bedrock console → model catalog shows each
> model's lifecycle status: `ACTIVE` vs `LEGACY`). An un-granted, retired, or
> marketplace-gated id returns an error surfaced by the API as
> `503 AI backend unavailable`.
>
> `rootstock.rag.blob.backend` defaults to `s3` because Bedrock Knowledge Base
> ingestion requires real S3 — there's no local/offline substitute (the
> `filesystem` backend still exists for unit tests, but documents written
> there are invisible to Bedrock and will never actually get indexed).

## Knowledge base / RAG

Tenant-scoped document ingestion + retrieval, backed entirely by an **AWS
Bedrock Knowledge Base**. Tenant comes from the `X-Tenant-Id` header (`default`
if absent) — a stub until real auth lands.

This app is a thin control plane around Bedrock, not a vector database itself:
parsing, chunking, embedding, storage, and similarity search all happen inside
AWS. What this app owns is the document/version/job bookkeeping (in its own
Postgres) and the tenant-scoping contract with Bedrock (a `tenant_id`, plus
`document_id`/`document_version_id`, tagged onto every uploaded object via an
S3 `.metadata.json` sidecar and filtered on at query time).

**Documents & versions**
- `POST /api/rag/documents` (multipart `file`, optional `sourceKey`, `displayName`)
  → uploads the file to S3 under a key scoped to this exact tenant/document/
  version (`rag-kb/<tenantId>/<documentId>/v<versionNo>`), plus a
  `.metadata.json` sidecar Bedrock's data source reads for tenant filtering.
  This same object serves both downloads and Bedrock ingestion — no separate
  content-addressed copy. Creates version 1, queues a sync job. Re-POST the
  same `sourceKey`, or `POST /api/rag/documents/{id}/versions`, to add a
  version.
- Background poller triggers a Bedrock Knowledge Base data-source sync
  (Bedrock owns parsing/chunking/embedding entirely) and waits for it to
  report success — including a check that it actually processed something,
  not just "completed" over an empty/misconfigured data source. Watch
  progress at `GET /api/rag/jobs`.
- `POST /api/rag/documents/{id}/versions/{n}/activate` — roll forward/back;
  `.../reindex` re-syncs; `GET .../content` downloads; `DELETE` purges the
  Bedrock-facing S3 object and triggers a cleanup sync.

**Query profiles** — named, versioned bundles of query-time knobs only
(chunking/embedding are fixed at the Bedrock Knowledge Base/data-source level,
not per-profile): top-k, similarity threshold, chat model id (not yet wired —
see Known limitations), prompt template, and optional reranking.
- `GET/POST /api/rag/profiles`, `GET /api/rag/profiles/{id}`,
  `GET/POST /api/rag/profiles/{id}/versions` (editing makes a new version).
- `POST /api/rag/profiles/{id}/activate` — always an immediate pointer flip;
  there's no re-indexing to wait for anymore since a profile no longer
  controls how documents are chunked/embedded.

**Query** — `POST /api/rag/query` `{ question, profileId?, topK?, similarityThreshold? }`
→ `{ answer, grounded, citations[], profileId, … }`. Calls Bedrock's `Retrieve`
API (filtered to the tenant's active document versions, optionally reranked —
see the profile's reranker settings), grounds the profile's prompt template in
the results, and answers via the Bedrock chat model.

Config (`rootstock.rag.*` in `application.yml`, all env-overridable — see the
Configuration table above for the Bedrock/blob-store keys):

| Setting | Env | Default |
|---|---|---|
| Ingestion poller | `rootstock.rag.ingest.poller-enabled` | `true` |
| Reranker model (when a profile enables it) | — | `cohere.rerank-v3-5:0` (only model currently available in this account/region) |

**Frontend** — the `/knowledge` page (React) drives all of the above:
- **Documents** tab — drag-and-drop upload with progress; expandable version rows
  (activate / reindex / download / delete); add-a-version dropzone per document.
- **Tuning** tab — edit a profile's top-k / similarity threshold / reranker /
  prompt template, "Save version" vs "Save & activate" (always immediate now),
  version history with per-version activate.
- **Playground** tab — ask a question, see the grounded answer with citation
  chips; optional top-k / threshold overrides.
- **Activity** tab — the ingestion job feed, auto-refreshing while work is queued.

A **Tenant** field in the header sets the `X-Tenant-Id` header for every request
(stored in `localStorage`).

**Known limitations:**
- `chatModelId` and `maxContextTokens` are stored on a profile and exposed via
  the API, but nothing reads them yet — every query uses the single globally
  configured `BEDROCK_MODEL`, and there's no context-token budget/truncation
  logic. Not shown in the Tuning tab so the UI doesn't imply control that
  doesn't exist.
- The Bedrock Knowledge Base's S3 data source currently scans the whole
  configured bucket, not just the app's own prefix — harmless (untagged
  objects never match a tenant filter) but wastes embedding cost on every sync
  if the bucket has other content in it.

## Tests

```bash
cd backend && ./mvnw test
cd frontend && npm run build && npm run lint
```

See **[TESTING.md](TESTING.md)** for what each suite covers and a full manual
walkthrough (API + UI) of the Customer and RAG features.

## Not yet included

Authentication/authorization (tenant is a header stub), CI, deployment
manifests, and Bedrock model fine-tuning. Each is its own follow-up.
