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
  `{ "reply": "...", "conversationId": "..." }` (503 with a clear message until
  AWS Bedrock is configured). Send that `conversationId` back to continue the
  thread — see [Conversations](#conversations).
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

## Authentication

Every `/api/**` route needs a valid **AWS Cognito ID token** as
`Authorization: Bearer <token>`. The only exceptions are `POST /api/auth/login`,
`POST /api/auth/refresh` and `GET /api/health` (liveness, so the UI can tell
"backend down" from "not signed in").

- `POST /api/auth/login` `{email, password}` → the backend calls Cognito's
  `InitiateAuth` server-side and returns Cognito's own tokens. The frontend
  never talks to AWS directly and holds no AWS credentials.
- `POST /api/auth/refresh` `{refreshToken}` → a fresh ID token.
- `GET /api/auth/me` → the caller's id, email, tenant, role and groups, as the
  verified token describes them. This is what the UI gates itself on.

Tokens are validated against the user pool's JWKS and pinned three ways: to the
pool (`iss`), to this app client (`aud`), and to **ID** tokens (`token_use`).
The last one matters — Cognito signs access tokens with the same keys, and on
this pool's plan an access token carries neither `custom:tenant_id` nor
`custom:role`, so accepting one would authenticate a tenantless request.

**Roles** (the `custom:role` attribute, mapped to a Spring Security authority):

| Role | Can |
| --- | --- |
| `VIEWER` | query, browse documents, download |
| `EDITOR` | + upload, add versions, reindex, delete |
| `ADMIN` | + profile tuning, group and document-access management; sees every document in the tenant regardless of access groups |

**Access groups** (Cognito groups, arriving as the `cognito:groups` claim)
decide what a caller can *see* rather than what they can *do*. A document
tagged with one or more groups is only retrievable by their members; a document
with no tags carries a synthetic `__public__` group every caller also carries,
so it stays visible tenant-wide. Enforcement is a `listContains` clause ANDed
into the Bedrock `Retrieve` filter, applied server-side by Bedrock — an
excluded chunk is never returned, so the chat model never sees it and there is
nothing to leak through the answer.

Changing a document's grants (`PUT /api/rag/documents/{id}/access-groups`)
rewrites the S3 `.metadata.json` sidecar and queues a re-sync, because Bedrock
filters on its own copy of the grants and only re-reads it on the next
ingestion job. **Until that job finishes, the previous grants still apply.**

Configuration (`rootstock.auth.cognito.*`, all overridable by env var):
`region`/`AWS_REGION`, `user-pool-id`/`COGNITO_USER_POOL_ID`,
`client-id`/`COGNITO_CLIENT_ID`. These are public OIDC identifiers, not
secrets. The app's IAM identity needs `cognito-idp:InitiateAuth` plus the
group-admin actions (`CreateGroup`, `ListGroups`, `AdminAddUserToGroup`,
`AdminRemoveUserFromGroup`), scoped to that one pool.

**Creating users.** `POST /api/rag/users` (ADMIN only) takes
`{email, password, role, groups?}` and creates the user in Cognito, ready to
sign in — the password is set as permanent, because this app answers no auth
challenges and a `FORCE_CHANGE_PASSWORD` account could never get past login.
There is deliberately **no tenant field**: the backend takes it from the
caller's token, so an admin can hand out any role, including `ADMIN`, but only
ever inside the tenant they already administer. The Access tab has a form for
it.

A tenant's **first** admin can't come from there — with no admin yet, nothing
could authorize the call. That stays an out-of-band `aws cognito-idp
admin-create-user` + `admin-set-user-password` (setting `custom:tenant_id` and
`custom:role=ADMIN`), gated by IAM rather than by this application. There is no
self-service sign-up.

## Conversations

Both `/api/chat` and `/api/rag/query` are conversational. Send the
`conversationId` from a response back with the next message to continue the
thread; omit it to start a new one. History is **stored server-side** (the
`conversation` and `chat_message` tables), so it survives a page refresh, a
restart and more than one instance — and can't be rewritten by whoever holds
the token, which a client-supplied transcript could be.

A thread belongs to one person: every lookup is by (id, tenant, Cognito `sub`)
together, so an id from somewhere else resolves to a 404 rather than to someone
else's conversation. Chat and RAG threads are kept apart — they answer under
different prompts, so continuing one as the other is a 400.

- `GET /api/chat/conversations`, `GET /api/rag/conversations` — your threads,
  newest activity first, titled from their opening message.
- `GET …/conversations/{id}` — the stored transcript.
- `DELETE …/conversations/{id}` — messages cascade.

**Only the last 20 turns** ride along with a new message
(`ConversationService.HISTORY_TURNS`). That's a cost and context-window bound,
not a nicety: every turn is re-sent on every request and Bedrock charges for all
of it. The cut always lands on a turn boundary, so the model never sees an
answer whose question was dropped.

**RAG follow-ups get rewritten before retrieval.** Similarity search only sees
the text it's given, so *"and what about its price?"* would match nothing —
the thing being priced is in an earlier turn. A condense step turns the
follow-up into a standalone query first, and the response's `retrievalQuery`
says what was actually searched for, so the rewrite is inspectable rather than
invisible. It costs one extra model call per follow-up, and none on the first
message of a thread. The answer is still generated against the question as
typed; only retrieval uses the rewrite.

## Knowledge base / RAG

Tenant-scoped document ingestion + retrieval, backed entirely by an **AWS
Bedrock Knowledge Base**. Tenant, role and group membership come from the
signed-in user's Cognito ID token — see [Authentication](#authentication).

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
not per-profile): top-k, similarity threshold, chat model id and max context
tokens (editable, not yet wired to behavior — see Roadmap), prompt template,
and optional reranking.
- `GET/POST /api/rag/profiles`, `GET /api/rag/profiles/{id}`,
  `GET/POST /api/rag/profiles/{id}/versions` (editing makes a new version).
- `POST /api/rag/profiles/{id}/activate` — always an immediate pointer flip;
  there's no re-indexing to wait for anymore since a profile no longer
  controls how documents are chunked/embedded.

**Query** — `POST /api/rag/query`
`{ question, conversationId?, profileId?, topK?, similarityThreshold? }`
→ `{ answer, grounded, citations[], conversationId, retrievalQuery, profileId, … }`.
Calls Bedrock's `Retrieve` API (filtered to the tenant's active document
versions and the caller's access groups, optionally reranked — see the profile's
reranker settings), grounds the profile's prompt template in the results, and
answers via the Bedrock chat model. Follow-ups are supported — see
[Conversations](#conversations).

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
  prompt template / chat model override / max context tokens (the last two
  save but don't affect behavior yet — see Roadmap), "Save version" vs
  "Save & activate" (always immediate now), version history with per-version
  activate.
- **Playground** tab — ask a question, see the grounded answer with citation
  chips; optional top-k / threshold overrides.
- **Activity** tab — the ingestion job feed, auto-refreshing while work is queued.

The header shows who you are signed in as (email, role, tenant, groups) and a
sign-out button. The tenant is no longer typed in — it comes from the token.
Viewers don't see the upload dropzone or the version actions; only admins see
the **Tuning** and **Access** tabs.

## Roadmap

Not yet functional, but with groundwork already in place so a future release
doesn't need a schema/UI change to add them:

- **Per-profile chat model** (`chatModelId`) — the Tuning tab lets you set an
  override per profile and it's saved with the profile, but every query still
  answers via the single globally configured `BEDROCK_MODEL`. Wiring this up
  means `RagQueryService` passing the profile's model id through to
  `ChatService` instead of always using the default `ChatClient`.
- **Context-token budgeting** (`maxContextTokens`) — same story: editable and
  saved, not yet enforced. Wiring this up means truncating/prioritizing
  retrieved chunks in `RagQueryService.renderContext()` against a token
  budget instead of concatenating everything retrieved.

## Tests

```bash
cd backend && ./mvnw test
cd frontend && npm run build && npm run lint
```

See **[TESTING.md](TESTING.md)** for what each suite covers and a full manual
walkthrough (API + UI) of the Customer and RAG features.

## Not yet included

SSO/federation to an enterprise IdP, password reset, MFA, audit logging (all
Cognito configuration layered onto what's here rather than rewrites), CI,
deployment manifests, and Bedrock model fine-tuning. Each is its own follow-up.

Conversation history is capped by turn count rather than tokens, and old threads
are never pruned — both fine at this size, both worth revisiting before real
traffic.
