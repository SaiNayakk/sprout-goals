-- Goals: pots invested in one share, the money that went into them and the buys made with it, and
-- round-ups from the customer's UPI spends.

CREATE TABLE pots (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    name         text NOT NULL,
    symbol       text NOT NULL,
    target_paise bigint NOT NULL CHECK (target_paise > 0),
    target_date  date,
    status       text NOT NULL CHECK (status IN ('OPEN', 'REACHED', 'CLOSED')),
    created_at   timestamptz NOT NULL,
    reached_at   timestamptz,
    closed_at    timestamptz
);
CREATE INDEX pots_by_user ON pots (user_id, created_at DESC);

-- Money into a pot (CONTRIBUTION, ROUND_UPS) and buys of its share (PURCHASE). A purchase's amount is
-- an estimate while PENDING and what the shares cost with charges once DONE.
CREATE TABLE movements (
    id              uuid PRIMARY KEY,
    seq             bigserial NOT NULL,          -- the order they happened in, even within one instant
    pot_id          uuid NOT NULL REFERENCES pots (id),
    user_id         uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('CONTRIBUTION', 'ROUND_UPS', 'PURCHASE')),
    idempotency_key text,
    amount_paise    bigint NOT NULL CHECK (amount_paise >= 0),
    quantity        bigint,
    price_paise     bigint,
    order_id        uuid,
    status          text NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    reason          text,
    at              timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX movements_by_pot ON movements (pot_id, seq DESC);
CREATE INDEX movements_pending ON movements (updated_at) WHERE status = 'PENDING';

CREATE TABLE round_up_settings (
    user_id    uuid PRIMARY KEY,
    enabled    boolean NOT NULL,
    round_to   int NOT NULL CHECK (round_to IN (10, 50, 100)),
    multiplier int NOT NULL CHECK (multiplier BETWEEN 1 AND 3),
    pot_id     uuid REFERENCES pots (id),
    updated_at timestamptz NOT NULL
);

-- One row per spend rounded up (the spend's id makes reading the feed twice harmless). sweep_id is set
-- when it is claimed by a sweep.
CREATE TABLE round_ups (
    spend_id     uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    pot_id       uuid NOT NULL REFERENCES pots (id),
    spent_paise  bigint NOT NULL,
    payee_name   text NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    sweep_id     uuid,
    at           timestamptz NOT NULL
);
CREATE INDEX round_ups_waiting ON round_ups (user_id) WHERE sweep_id IS NULL;
CREATE INDEX round_ups_by_user ON round_ups (user_id, at DESC);

-- One AutoPay debit for a batch of round-ups. Its id is the debit's reference, so asking again is safe.
CREATE TABLE sweeps (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    pot_id       uuid NOT NULL REFERENCES pots (id),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    status       text NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    reason       text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);
CREATE INDEX sweeps_pending ON sweeps (updated_at) WHERE status = 'PENDING';
CREATE INDEX sweeps_by_user ON sweeps (user_id, created_at DESC);

-- Where reading the spends feed got to.
CREATE TABLE cursors (
    name     text PRIMARY KEY,
    position bigint NOT NULL
);
