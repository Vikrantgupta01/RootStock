-- Cases that survive a restart: each run, and the graph's checkpoints.
--
-- A case can wait days before a human-review node, so a paused run must not
-- live only in memory. The checkpoints hold the graph's state (Java-serialized:
-- everything a node writes into state is Serializable); case_run holds what the
-- screens show (status, events, the result as JSON) so lists and history load
-- without deserializing state. A reviewer's decision is part of the run: its
-- events, and the audit entry the human-review node writes.

CREATE TABLE case_run (
    run_id         VARCHAR(36)  PRIMARY KEY,
    case_id        VARCHAR(36)  NOT NULL UNIQUE,
    pack           VARCHAR(128) NOT NULL,
    graph          VARCHAR(128) NOT NULL,
    graph_version  VARCHAR(64),
    -- Cognito `sub` of the member who submitted the case.
    started_by     VARCHAR(128) NOT NULL,
    -- What was submitted, as submitted (e.g. a member's visit notes).
    input          TEXT         NOT NULL,
    -- RUNNING, PAUSED, COMPLETED, FAILED or PARKED.
    status         VARCHAR(16)  NOT NULL,
    pause_node     VARCHAR(128),
    pause_before   BOOLEAN,
    -- Why it failed or was parked.
    error          TEXT,
    trace_id       VARCHAR(64),
    started_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The run's events, in order, and its channels when it last stopped: for the
    -- screens only. Resuming uses the checkpoints.
    events         JSONB        NOT NULL DEFAULT '[]',
    result         JSONB        NOT NULL DEFAULT '{}'
);

-- The case list: newest first; one member's own cases.
CREATE INDEX ix_case_run_started ON case_run (started_at DESC);
CREATE INDEX ix_case_run_owner ON case_run (started_by, started_at DESC);

-- LangGraph4j checkpoints, one row each, per thread (a thread is a run).
CREATE TABLE case_checkpoint (
    seq            BIGSERIAL    PRIMARY KEY,
    thread_id      VARCHAR(64)  NOT NULL,
    checkpoint_id  VARCHAR(64)  NOT NULL,
    node_id        VARCHAR(128),
    next_node_id   VARCHAR(128),
    state          BYTEA        NOT NULL,
    saved_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_case_checkpoint_thread ON case_checkpoint (thread_id, seq DESC);
CREATE UNIQUE INDEX ux_case_checkpoint_id ON case_checkpoint (thread_id, checkpoint_id);
