-- A case goes through several short runs (e.g. intake, then a coordinator's
-- decision), each of one graph; between runs no graph is held open. The case
-- file keeps how the case stands, which graph comes next and who may start it,
-- and the channels it carries into that run (Java-serialized, like
-- checkpoints). case_run now holds every run of a case, not only one.

ALTER TABLE case_run DROP CONSTRAINT case_run_case_id_key;
CREATE INDEX ix_case_run_case ON case_run (case_id, started_at DESC);

CREATE TABLE case_file (
    case_id      VARCHAR(36)  PRIMARY KEY,
    pack         VARCHAR(128) NOT NULL,
    -- RUNNING while a run is going; otherwise the outcome of the last run, as its
    -- graph says (e.g. AWAITING_DECISION, DONE), or FAILED / PARKED.
    status       VARCHAR(32)  NOT NULL,
    -- The graph the case goes on with, started by its trigger; null when finished.
    next_graph   VARCHAR(128),
    -- That graph's approverRoles, comma separated: who may start it.
    waiting_for  TEXT,
    started_by   VARCHAR(128) NOT NULL,
    input        TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_run_id  VARCHAR(36),
    state        BYTEA
);

-- Queues: cases in a status, oldest first.
CREATE INDEX ix_case_file_status ON case_file (status, updated_at);
