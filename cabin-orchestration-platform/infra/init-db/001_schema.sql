-- Enable TimescaleDB
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Device registry
CREATE TABLE IF NOT EXISTS device (
    device_id    TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL,
    capabilities TEXT[],
    protocol     TEXT,
    config       JSONB,
    created_at   TIMESTAMPTZ DEFAULT now(),
    updated_at   TIMESTAMPTZ DEFAULT now()
);

-- Ontology-governed device catalog. Discovery writes candidates only;
-- operational authority requires an explicit identity binding plus
-- ADMITTED + CONFORMING + ENABLED lifecycle states.
CREATE TABLE IF NOT EXISTS device_catalog_entry (
    device_id            VARCHAR(128) PRIMARY KEY,
    ontology_id          VARCHAR(256),
    name                 VARCHAR(256) NOT NULL,
    device_type          VARCHAR(64) NOT NULL,
    capabilities_json    TEXT NOT NULL,
    protocol_adapter     VARCHAR(64) NOT NULL,
    connection_string    TEXT NOT NULL,
    location             VARCHAR(64) NOT NULL,
    identity_assurance   VARCHAR(32) NOT NULL,
    admission_status     VARCHAR(32) NOT NULL,
    configuration_status VARCHAR(32) NOT NULL,
    enablement_status    VARCHAR(32) NOT NULL,
    updated_at           BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_observation_candidate (
    candidate_id       VARCHAR(64) PRIMARY KEY,
    source_type        VARCHAR(64) NOT NULL,
    source_identity    VARCHAR(512) NOT NULL,
    proposed_device_id VARCHAR(128),
    metadata_json      TEXT NOT NULL,
    disposition        VARCHAR(32) NOT NULL,
    first_seen         BIGINT NOT NULL,
    last_seen          BIGINT NOT NULL,
    seen_count         BIGINT NOT NULL,
    rejection_reason   TEXT,
    rejected_by        VARCHAR(256),
    rejected_at        BIGINT,
    UNIQUE (source_type, source_identity)
);
CREATE INDEX IF NOT EXISTS idx_device_candidate_last_seen
    ON device_observation_candidate (last_seen DESC);

CREATE TABLE IF NOT EXISTS device_identity_binding (
    source_type     VARCHAR(64) NOT NULL,
    source_identity VARCHAR(512) NOT NULL,
    device_id       VARCHAR(128) NOT NULL UNIQUE,
    actor_id        VARCHAR(256) NOT NULL,
    bound_at        BIGINT NOT NULL,
    PRIMARY KEY (source_type, source_identity)
);

CREATE TABLE IF NOT EXISTS device_lifecycle_decision (
    decision_id  VARCHAR(64) PRIMARY KEY,
    candidate_id VARCHAR(64),
    device_id    VARCHAR(128),
    action       VARCHAR(64) NOT NULL,
    actor_type   VARCHAR(32) NOT NULL,
    actor_id     VARCHAR(256) NOT NULL,
    reason       TEXT,
    decided_at   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_decision_candidate
    ON device_lifecycle_decision (candidate_id, decided_at DESC);

-- Ontology: platform_auth_session + platform_session_credential.
-- The browser receives the opaque credential; only its SHA-256 digest is
-- durable. Google access tokens never enter this table.
CREATE TABLE IF NOT EXISTS platform_auth_session (
    credential_hash CHAR(64) PRIMARY KEY,
    subject_email   VARCHAR(320) NOT NULL,
    auth_source     VARCHAR(32) NOT NULL,
    created_at      BIGINT NOT NULL,
    expires_at      BIGINT NOT NULL,
    last_seen_at    BIGINT NOT NULL,
    revoked_at      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_platform_auth_session_subject
    ON platform_auth_session (subject_email, expires_at);

-- Time-series telemetry
CREATE TABLE IF NOT EXISTS telemetry (
    time      TIMESTAMPTZ NOT NULL,
    device_id TEXT NOT NULL,
    metric    TEXT NOT NULL,
    value     DOUBLE PRECISION,
    unit      TEXT,
    meta      JSONB
);
SELECT create_hypertable('telemetry', 'time', if_not_exists => TRUE);

-- Events / alerts
CREATE TABLE IF NOT EXISTS cabin_event (
    event_id    TEXT PRIMARY KEY,
    time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    device_id   TEXT,
    event_type  TEXT NOT NULL,
    severity    TEXT NOT NULL,
    payload     JSONB
);
CREATE INDEX ON cabin_event (time DESC);

-- Automation rules
CREATE TABLE IF NOT EXISTS automation_rule (
    rule_id     TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    enabled     BOOLEAN DEFAULT TRUE,
    trigger     JSONB NOT NULL,
    conditions  JSONB,
    actions     JSONB NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT now()
);
