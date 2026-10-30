-- GET /me/sync reads a user's newest batches; the (user_id, idempotency_key) key can't order them.
CREATE INDEX ingest_batches_user_received_idx ON vitallink.ingest_batches (user_id, received_at DESC);
