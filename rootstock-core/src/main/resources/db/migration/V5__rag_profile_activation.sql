-- Tracks a blue/green profile switch: while a layout-changing profile is being
-- re-indexed, the previous profile stays active and queryable; the monitor flips
-- the pointer only once every REINDEX job for the target profile has succeeded.

CREATE TABLE rag_profile_activation (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            VARCHAR(128) NOT NULL,
    target_profile_id    UUID NOT NULL REFERENCES rag_profile (id) ON DELETE CASCADE,
    previous_profile_id  UUID REFERENCES rag_profile (id) ON DELETE SET NULL,
    total_jobs           INTEGER      NOT NULL,
    state                VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_message        VARCHAR(2000),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ
);

-- At most one in-flight activation per tenant.
CREATE UNIQUE INDEX ux_rag_profile_activation_pending
    ON rag_profile_activation (tenant_id) WHERE state = 'PENDING';
