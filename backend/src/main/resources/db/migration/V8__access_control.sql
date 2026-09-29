-- Document-level access control.
--
-- Identity lives in AWS Cognito, not here: users, their tenant (custom:tenant_id),
-- their role (custom:role), and their group memberships (cognito:groups) all
-- arrive as signed claims on each request's ID token. There is deliberately no
-- app_user or user_group table -- mirroring Cognito's own directory locally would
-- just create a second, staler source of truth for the one thing Cognito is
-- authoritative about.
--
-- What *is* this app's own concern: which groups may see which document. That
-- needs a local, indexable join (the retrieval path builds a filter from it on
-- every query), plus a local list of "what groups exist" so the admin UI can
-- offer them without an API round-trip per render.

-- Local mirror of the Cognito groups that exist, per tenant. Cognito remains
-- authoritative for *membership*; this only tracks existence, so documents can
-- be tagged against something with referential integrity.
CREATE TABLE access_group (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_access_group_tenant_name UNIQUE (tenant_id, name)
);

-- Which groups may see a document. No rows for a document = visible to everyone
-- in its tenant (preserves the pre-access-control behaviour, so nothing already
-- uploaded silently disappears).
CREATE TABLE document_access_group (
    document_id  UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    group_id     UUID NOT NULL REFERENCES access_group (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, group_id)
);

CREATE INDEX ix_document_access_group_group ON document_access_group (group_id);

-- General-purpose document metadata, queryable locally. The same attributes get
-- mirrored into the S3 .metadata.json sidecar for Bedrock's retrieval filter,
-- but that copy is derived -- this column is the source of truth and the thing
-- local listing/filtering reads, so browsing documents never needs Bedrock.
ALTER TABLE document ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';
CREATE INDEX ix_document_metadata ON document USING gin (metadata jsonb_path_ops);
