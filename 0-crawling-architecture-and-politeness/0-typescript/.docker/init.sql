-- init.sql: create the page table on first Postgres startup.
-- TypeORM synchronize is disabled (synchronize:false); this file is the
-- single source of schema truth so the table definition never drifts silently.
CREATE TABLE IF NOT EXISTS page (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    url         VARCHAR     UNIQUE NOT NULL,
    host        VARCHAR     NOT NULL,
    html_body   TEXT        NOT NULL,
    content_type VARCHAR    NOT NULL,
    status_code INT         NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
