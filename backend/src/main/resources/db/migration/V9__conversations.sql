-- Conversation history for /api/chat and /api/rag/query.
--
-- Until now every request was a fresh conversation: the prompt carried one user
-- message and nothing else, and the frontend's transcript existed only in React
-- state. Persisting it here (rather than replaying a client-supplied array)
-- keeps the history server-owned, so it survives a refresh or restart, works
-- with more than one instance, and can't be rewritten by whoever holds the
-- token.
--
-- Ownership is the pair (tenant_id, user_id): user_id is the Cognito `sub` off
-- the verified ID token. A conversation is private to the person who started
-- it -- unlike documents, which are shared within a tenant and gated by access
-- groups, there is no sharing model for chat.

CREATE TABLE conversation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(128) NOT NULL,
    user_id     VARCHAR(128) NOT NULL,
    -- CHAT (plain assistant) or RAG (grounded, retrieval-backed). Kept apart so
    -- the two surfaces don't interleave in one another's history.
    kind        VARCHAR(16)  NOT NULL,
    -- Derived from the opening question, for listing; never sent to the model.
    title       VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The conversation list: one person's, newest activity first.
CREATE INDEX ix_conversation_owner ON conversation (tenant_id, user_id, kind, updated_at DESC);

CREATE TABLE chat_message (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID         NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    -- Ordering is explicit rather than by created_at: two messages written in
    -- the same transaction can share a timestamp, and the turn order is the one
    -- thing the model must see exactly right.
    seq              INTEGER      NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_chat_message_seq UNIQUE (conversation_id, seq)
);

CREATE INDEX ix_chat_message_conversation ON chat_message (conversation_id, seq);
