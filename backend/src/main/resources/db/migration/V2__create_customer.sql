-- First domain entity: Customer.

CREATE TABLE customer (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200)  NOT NULL,
    email       VARCHAR(320)  NOT NULL,
    company     VARCHAR(200),
    phone       VARCHAR(40),
    status      VARCHAR(20)   NOT NULL DEFAULT 'PROSPECT',
    notes       VARCHAR(5000),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness on email.
CREATE UNIQUE INDEX ux_customer_email_lower ON customer (lower(email));

CREATE INDEX ix_customer_status ON customer (status);
