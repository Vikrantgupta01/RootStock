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

### Enabling embeddings / the pgvector VectorStore

Embeddings are **off** in the scaffold: `spring.ai.model.embedding: none` and the
pgvector store auto-configuration is listed under `spring.autoconfigure.exclude`
in `application.yml`. The `vector` extension is already installed by
`V1__init.sql`, so the database is ready. To turn on RAG:

1. Remove `PgVectorStoreAutoConfiguration` from `spring.autoconfigure.exclude`.
2. Set `spring.ai.model.embedding: bedrock-titan` (or another provider), and add
   its starter if needed (`spring-ai-starter-model-bedrock` is already on the
   classpath and provides Titan embeddings).
3. Ensure `spring.ai.vectorstore.pgvector.dimensions` matches the embedding model
   (Titan Text Embeddings v2 = 1024).
4. Inject `VectorStore` where you need it.

## Tests

```bash
cd backend && ./mvnw test
```

- `ChatControllerTest`, `HealthControllerTest` — MVC slice tests, no Docker/AWS.
- `RootStockApplicationTests` — full context against a Testcontainers Postgres with
  Flyway; **skips automatically** when Docker is not running.

```bash
cd frontend && npm run build && npm run lint
```

## Not yet included

Domain entities, authentication/authorization, CI, deployment manifests, and the
RAG ingestion pipeline. Each is its own follow-up.
