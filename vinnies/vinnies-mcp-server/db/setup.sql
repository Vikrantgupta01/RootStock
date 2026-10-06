-- =====================================================================
-- Vinnies mock app: complete database setup. All data is fictional.
--
-- For the demo the mock app shares RootStock's database (rootstock_app) and
-- login, but keeps everything in its own schema, vinnies_mock, so it never
-- touches RootStock's tables. A real client system would have its own database;
-- this is a demo shortcut.
--
-- Run connected to the rootstock_app database as the login the app uses
-- (VINNIES_DB_USERNAME). Safe to run again: it only creates what is missing.
-- The app does not create or change tables itself; it checks on startup that
-- these exist and match its entities (hibernate ddl-auto: validate).
--
-- To remove everything the mock app owns, irreversibly:
--   DROP SCHEMA vinnies_mock CASCADE;
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS vinnies_mock;
SET search_path TO vinnies_mock;

-- ---------------------------------------------------------------------
-- Households and their members (ontology: Household extends core.Party:
-- suburb, contact, consentGiven, members -> Person). find_household matches on
-- name, suburb and phone.
--
-- Ids are assigned by the application, not the database, so seeding with a
-- fixed random seed produces identical rows every time. `ref` is the short,
-- stable identifier tools hand out (householdRef in the tool catalogue).
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS household (
    id             UUID         PRIMARY KEY,
    ref            VARCHAR(16)  NOT NULL UNIQUE,     -- e.g. HH-0001
    family_name    VARCHAR(100) NOT NULL,            -- how the household is usually referred to
    suburb         VARCHAR(100) NOT NULL,
    postcode       VARCHAR(4)   NOT NULL,
    phone          VARCHAR(20),                      -- PII (ontology: Household.contact)
    consent_given  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_household_suburb ON household (lower(suburb));

CREATE TABLE IF NOT EXISTS person (
    id             UUID         PRIMARY KEY,
    household_id   UUID         NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    given_name     VARCHAR(100) NOT NULL,            -- PII
    family_name    VARCHAR(100) NOT NULL,            -- PII
    relationship   VARCHAR(20)  NOT NULL
        CHECK (relationship IN ('PRIMARY_CONTACT', 'PARTNER', 'CHILD', 'OTHER_ADULT')),
    birth_year     SMALLINT     CHECK (birth_year BETWEEN 1900 AND 2100)
);

CREATE INDEX IF NOT EXISTS ix_person_household ON person (household_id);

-- Exactly one primary contact per household: the person a volunteer asks for.
CREATE UNIQUE INDEX IF NOT EXISTS ux_person_primary_contact ON person (household_id)
    WHERE relationship = 'PRIMARY_CONTACT';

-- ---------------------------------------------------------------------
-- Past assistance given to a household (ontology: Assistance extends
-- core.Action, with category and amountAud). Read by get_assistance_history:
-- date, type, amount. category uses the ontology's NeedCategory vocabulary.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assistance (
    id            UUID          PRIMARY KEY,
    ref           VARCHAR(16)   NOT NULL UNIQUE,      -- e.g. AS-00001
    household_id  UUID          NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    assisted_on   DATE          NOT NULL,
    category      VARCHAR(32)   NOT NULL
        CHECK (category IN ('FOOD', 'ENERGY_BILL', 'RENT')),
    amount_aud    NUMERIC(10,2) NOT NULL CHECK (amount_aud >= 0),
    description   VARCHAR(255)
);

-- History lookups are always "this household, most recent first".
CREATE INDEX IF NOT EXISTS ix_assistance_household_date ON assistance (household_id, assisted_on DESC);

-- ---------------------------------------------------------------------
-- Local services volunteers refer households to (ontology: Service, the target
-- of Referral.refersTo). Read by search_local_services(needType, suburb), which
-- returns address, hours and eligibility.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS local_service (
    id            UUID          PRIMARY KEY,
    ref           VARCHAR(16)   NOT NULL UNIQUE,      -- e.g. SVC-001
    name          VARCHAR(150)  NOT NULL,
    need_category VARCHAR(32)   NOT NULL
        CHECK (need_category IN ('FOOD', 'ENERGY_BILL', 'RENT')),
    suburb        VARCHAR(100)  NOT NULL,
    postcode      VARCHAR(4)    NOT NULL,
    address       VARCHAR(255)  NOT NULL,
    phone         VARCHAR(20),
    hours         VARCHAR(255)  NOT NULL,             -- e.g. "Mon-Fri 9:00-15:00"
    eligibility   VARCHAR(500)  NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_local_service_need_suburb ON local_service (need_category, lower(suburb));
