-- frontier_event: append-only audit trail for every enqueue / enqueue-dup / dequeue action.
-- The index on action keeps dedup-rate analytics (how many enqueue-dup in the last hour?) cheap.
CREATE TABLE IF NOT EXISTS frontier_event (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    action      text        NOT NULL,
    url         text        NOT NULL,
    priority    int         NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_frontier_event_action ON frontier_event (action);
