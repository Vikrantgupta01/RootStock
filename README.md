# RootStock

AI-powered web app. Java 21 / Spring Boot backend with Spring AI wired to **AWS
Bedrock**, PostgreSQL + **pgvector** for persistence and embeddings, and a React +
TypeScript frontend.

This is a **scaffold**: it ships a health endpoint and one sample AI chat endpoint.
The real domain model arrives with the first feature.

## Layout

```
RootStock/
├── backend/     Spring Boot 4 · Java 21 · Maven · Spring AI (Bedrock Converse) · JPA · Flyway
│   └── compose.yaml   pgvector/pgvector:pg16 (auto-started in dev by Spring Boot)
└── frontend/    React 19 · TypeScript · Vite · React Router · TanStack Query
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | Project targets Java 21; a newer JDK on `PATH` is fine. |
| Node.js | 20+ | For the frontend. |
| Docker | any recent | Runs Postgres/pgvector locally; also used by the integration test. |
| AWS credentials | — | Only needed to actually call the chat endpoint. Needs an account with **Bedrock model access** for the configured model. Provide via the standard AWS chain (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_REGION`, an SSO profile, or an IAM role). |

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
# optional: export AWS_REGION=us-east-1  (+ credentials) to enable /api/chat
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
| `spring.ai.bedrock.converse.chat.options.model` | `BEDROCK_MODEL` | `us.anthropic.claude-sonnet-4-20250514-v1:0` |
| `rootstock.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |

> The default model id is a starting point only. Set `BEDROCK_MODEL` to a model
> or inference-profile id your AWS account has **explicitly been granted access
> to** in the chosen region (Bedrock console → *Model access*). An un-granted or
> retired id returns `404 ResourceNotFoundException`, surfaced by the API as
> `503 AI backend unavailable`.

## Knowledge base / RAG (phases 1–2: ingestion, storage, profiles, query)

Tenant-scoped document ingestion + retrieval over pgvector. Tenant comes from the
`X-Tenant-Id` header (`default` if absent) — a stub until real auth lands.

**Documents & versions**
- `POST /api/rag/documents` (multipart `file`, optional `sourceKey`, `displayName`)
  → stores the blob, creates version 1, queues ingestion. Re-POST the same
  `sourceKey`, or `POST /api/rag/documents/{id}/versions`, to add a version.
- Background poller parses (Apache Tika), chunks (overlapping character windows),
  embeds, writes to `vector_store_<dim>` tagged with the embedding model + chunk
  layout that produced each chunk. Watch progress at `GET /api/rag/jobs`.
- `POST /api/rag/documents/{id}/versions/{n}/activate` — roll forward/back;
  `.../reindex` re-embeds; `GET .../content` downloads; `DELETE` purges chunks.

**Tuning profiles** — named, versioned config bundles (chunking, embedding model,
top-k, threshold, prompt template, …).
- `GET/POST /api/rag/profiles`, `GET /api/rag/profiles/{id}`,
  `GET/POST /api/rag/profiles/{id}/versions` (editing makes a new version).
- `POST /api/rag/profiles/{id}/activate` — a query-time-only change flips
  immediately; a chunking/embedding change runs a **blue/green re-index**: the
  previous profile keeps serving until every re-index job for the new one
  succeeds, then the pointer flips and the old layout's chunks are cleaned up.
  Poll `GET /api/rag/profiles/activations/{activationId}`.

**Query** — `POST /api/rag/query` `{ question, profileId?, topK?, similarityThreshold? }`
→ `{ answer, grounded, citations[], profileId, … }`. Retrieves the tenant's active
indexed chunks under the profile's layout, grounds the profile's prompt template
in them, and answers via the Bedrock chat model (503 until a model is granted).

Config (`rootstock.rag.*` in `application.yml`, all env-overridable):

| Setting | Env | Default |
|---|---|---|
| Embedding model | `RAG_EMBEDDING_MODE` | `fake` (offline, deterministic) — set `bedrock` for Titan v2 |
| Blob store | `RAG_BLOB_BACKEND` | `filesystem` (`./data/blobs`) — set `s3` for S3/MinIO |
| S3 endpoint | `RAG_S3_ENDPOINT` | _(blank = real AWS)_ |
| Ingestion poller | `rootstock.rag.ingest.poller-enabled` | `true` |

Local S3 via MinIO:

```bash
docker compose -f backend/compose.yaml --profile s3 up -d
RAG_BLOB_BACKEND=s3 RAG_S3_ENDPOINT=http://localhost:9000 \
AWS_ACCESS_KEY_ID=rootstock AWS_SECRET_ACCESS_KEY=rootstock123 ./mvnw spring-boot:run
```

**Still to come:** the `/knowledge` UI (phase 3) and Bedrock model fine-tuning
jobs (phase 4).

## Tests

```bash
cd backend && ./mvnw test
```

- Controller slice tests (`*ControllerTest`) — no Docker/AWS.
- `RootStockApplicationTests`, `CustomerRepositoryTest`, `RagIngestionIntegrationTest`
  — full/JPA context against a Testcontainers Postgres with Flyway;
  **skip automatically** when Docker is not running.

```bash
cd frontend && npm run build && npm run lint
```

## Not yet included

Authentication/authorization (tenant is a header stub), CI, deployment manifests,
RAG retrieval/query + tunable profiles, the `/knowledge` UI, and Bedrock
fine-tuning. Each is its own follow-up.
