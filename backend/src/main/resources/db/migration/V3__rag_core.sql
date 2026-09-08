-- RAG subsystem: tenant-scoped profiles, documents, versions, and the ingestion
-- job ledger. All rows carry tenant_id (stub for real auth).

CREATE TABLE rag_profile (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             VARCHAR(128)  NOT NULL,
    name                  VARCHAR(128)  NOT NULL,
    version_no            INTEGER       NOT NULL,
    active                BOOLEAN       NOT NULL DEFAULT FALSE,
    chunking_strategy     VARCHAR(32)   NOT NULL,
    chunk_size            INTEGER       NOT NULL,
    chunk_overlap         INTEGER       NOT NULL,
    embedding_model_id    VARCHAR(64)   NOT NULL,
    chat_model_id         VARCHAR(256),
    top_k                 INTEGER       NOT NULL,
    similarity_threshold  DOUBLE PRECISION NOT NULL,
    reranker_enabled      BOOLEAN       NOT NULL DEFAULT FALSE,
    reranker_model        VARCHAR(128),
    max_context_tokens    INTEGER       NOT NULL,
    prompt_template       TEXT          NOT NULL,
    hybrid_search         BOOLEAN       NOT NULL DEFAULT FALSE,
    fine_tuned_model_arn  VARCHAR(512),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(128),
    CONSTRAINT ux_rag_profile_name_version UNIQUE (tenant_id, name, version_no)
);

-- At most one active profile per tenant.
CREATE UNIQUE INDEX ux_rag_profile_active ON rag_profile (tenant_id) WHERE active;

CREATE TABLE document (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(128) NOT NULL,
    source_key         VARCHAR(512) NOT NULL,
    display_name       VARCHAR(512) NOT NULL,
    content_type       VARCHAR(255),
    active_version_id  UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_document_source_key UNIQUE (tenant_id, source_key)
);

CREATE TABLE document_version (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    tenant_id      VARCHAR(128) NOT NULL,
    version_no     INTEGER      NOT NULL,
    blob_key       VARCHAR(128) NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    chunk_count    INTEGER      NOT NULL DEFAULT 0,
    error_message  VARCHAR(2000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    indexed_at     TIMESTAMPTZ,
    CONSTRAINT ux_document_version_no UNIQUE (document_id, version_no)
);

CREATE INDEX ix_document_version_status ON document_version (status);

ALTER TABLE document
    ADD CONSTRAINT fk_document_active_version
    FOREIGN KEY (active_version_id) REFERENCES document_version (id) ON DELETE SET NULL;

CREATE TABLE ingestion_job (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            VARCHAR(128) NOT NULL,
    kind                 VARCHAR(16)  NOT NULL,
    document_version_id  UUID REFERENCES document_version (id) ON DELETE CASCADE,
    profile_id           UUID REFERENCES rag_profile (id) ON DELETE CASCADE,
    state                VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    attempts             INTEGER      NOT NULL DEFAULT 0,
    max_attempts         INTEGER      NOT NULL DEFAULT 3,
    locked_by            VARCHAR(128),
    locked_at            TIMESTAMPTZ,
    error_message        VARCHAR(2000),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_ingestion_job_poll ON ingestion_job (state, created_at);
