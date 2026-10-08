-- A pot can be created with an optional Idempotency-Key, so a create replayed after a cell failover
-- makes the pot once. The key is unique per customer; pots made without one have none, and any
-- number of NULLs are allowed, so existing rows are untouched.

ALTER TABLE pots ADD COLUMN idempotency_key text;
ALTER TABLE pots ADD CONSTRAINT pots_user_idempotency_key_key UNIQUE (user_id, idempotency_key);
