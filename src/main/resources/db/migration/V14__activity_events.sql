CREATE TABLE activity_events (
    id               UUID PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL,
    actor_user_id    UUID,
    actor_user_type  TEXT,
    actor_code       TEXT,
    action           TEXT NOT NULL,
    subject_type     TEXT,
    subject_id       TEXT,
    outcome          TEXT NOT NULL,
    http_status      INT,
    http_method      TEXT,
    http_path        TEXT,
    detail           JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip               TEXT,
    user_agent       TEXT
);

CREATE INDEX idx_activity_occurred ON activity_events (occurred_at DESC);
CREATE INDEX idx_activity_actor ON activity_events (actor_user_id, occurred_at DESC);
CREATE INDEX idx_activity_subject ON activity_events (subject_type, subject_id, occurred_at DESC);

CREATE FUNCTION activity_events_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'activity_events is append-only';
END;
$$;

CREATE TRIGGER activity_events_no_update
    BEFORE UPDATE OR DELETE ON activity_events
    FOR EACH ROW EXECUTE FUNCTION activity_events_immutable();
