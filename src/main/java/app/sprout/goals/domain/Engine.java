package app.sprout.goals.domain;

import app.sprout.goals.config.GoalsProperties;
import app.sprout.goals.domain.Goals.Pot;
import app.sprout.goals.domain.Upstreams.Reply;
import app.sprout.goals.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work goals do on their own, each round: read the spends AutoPay shares and round them up, sweep
 * what is waiting into pots, and buy each pot's share with what it holds.
 *
 * <p>Every step can be repeated: a spend is rounded up once (its id), a sweep's debit and a buy's order
 * carry ids made before they are asked for, and an unknown outcome is asked again next round, never
 * guessed. A refused sweep or buy waits {@code retry-after} before it is tried again.
 */
@Component
public class Engine {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);
    /** Buys leave this much room for charges, so value plus charges stays within what the pot holds. */
    static final double CHARGES_ROOM = 1.005;

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Upstreams up;
    private final Goals goals;
    private final GoalsProperties props;

    public Engine(JdbcClient db, TransactionTemplate tx, Clock clock, Upstreams up, Goals goals, GoalsProperties props) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.up = up;
        this.goals = goals;
        this.props = props;
    }

    /** One round. Each part goes ahead even if another couldn't (e.g. payments down, market closed). */
    public void round() {
        try {
            readSpends();
        } catch (Unreachable e) {
            log.info("Spends not read this round: {}", e.getMessage());
        }
        try {
            sweep();
        } catch (Unreachable e) {
            log.info("Round-ups not swept this round: {}", e.getMessage());
        }
        try {
            settleBuys();
            buy();
        } catch (Unreachable e) {
            log.info("Pots not bought for this round: {}", e.getMessage());
        }
    }

    // ── round-ups ────────────────────────────────────────────────────────────

    /** Rounds up the spends of customers with round-ups on, reading the feed from where it got to. */
    int readSpends() {
        int added = 0;
        for (int page = 0; page < 5; page++) {
            long after = db.sql("SELECT position FROM cursors WHERE name = 'spends'").query(Long.class).optional().orElse(0L);
            Reply r = up.spends(after, 200);
            if (!r.ok()) {
                throw new Unreachable("payments answered " + r.status());
            }
            JsonNode spends = r.body().path("spends");
            for (JsonNode s : spends) {
                added += roundUp(s);
            }
            long next = r.body().path("next").asLong(after);
            db.sql("INSERT INTO cursors (name, position) VALUES ('spends', ?) ON CONFLICT (name) DO UPDATE SET position = EXCLUDED.position")
                    .param(next).update();
            if (spends.size() < 200) {
                break;
            }
        }
        return added;
    }

    private int roundUp(JsonNode spend) {
        UUID user = UUID.fromString(spend.path("userId").asText());
        Goals.Settings s = goals.settings(user);
        if (!s.enabled() || s.potId() == null) {
            return 0;
        }
        long spent = Money.paise(spend.path("amount").asText());
        long amount = Goals.roundUp(spent, s.roundTo(), s.multiplier());
        if (amount == 0) {
            return 0;
        }
        return db.sql("INSERT INTO round_ups (spend_id, user_id, pot_id, spent_paise, payee_name, amount_paise, at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (spend_id) DO NOTHING")
                .params(UUID.fromString(spend.path("id").asText()), user, s.potId(), spent, spend.path("payeeName").asText(), amount,
                        Timestamp.from(Instant.parse(spend.path("at").asText())))
                .update();
    }

    /** Asks again about sweeps whose outcome wasn't known, then sweeps what is waiting for everyone due. */
    void sweep() {
        for (UUID id : db.sql("SELECT id FROM sweeps WHERE status = 'PENDING' ORDER BY created_at LIMIT 50").query(UUID.class).list()) {
            debit(id);
        }
        Instant now = clock.instant();
        List<UUID> due = db.sql("""
                        SELECT r.user_id FROM round_ups r
                        WHERE r.sweep_id IS NULL
                          AND NOT EXISTS (SELECT 1 FROM sweeps s WHERE s.user_id = r.user_id
                                          AND (s.status = 'PENDING' OR (s.status = 'FAILED' AND s.updated_at > ?)))
                        GROUP BY r.user_id HAVING SUM(r.amount_paise) >= ?""")
                .params(Timestamp.from(now.minus(props.retryAfter())), Money.paise(props.sweepAt())).query(UUID.class).list();
        for (UUID user : due) {
            UUID id = claim(user, now);
            if (id != null) {
                debit(id);
            }
        }
    }

    /** Claims everything waiting for one sweep, into the pot round-ups go to now. */
    private UUID claim(UUID user, Instant now) {
        return tx.execute(s -> {
            UUID pot = goals.settings(user).potId();
            if (pot == null) {
                return null;
            }
            UUID id = UUID.randomUUID();
            long amount = db.sql("SELECT COALESCE(SUM(amount_paise), 0) FROM round_ups WHERE user_id = ? AND sweep_id IS NULL").param(user)
                    .query(Long.class).single();
            if (amount <= 0) {
                return null;
            }
            db.sql("INSERT INTO sweeps (id, user_id, pot_id, amount_paise, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, ?)")
                    .params(id, user, pot, amount, Timestamp.from(now), Timestamp.from(now)).update();
            db.sql("UPDATE round_ups SET sweep_id = ? WHERE user_id = ? AND sweep_id IS NULL").params(id, user).update();
            return id;
        });
    }

    /** Takes a sweep's money under AutoPay (the sweep's id is the debit's reference) and puts it in the pot. */
    private void debit(UUID sweepId) {
        Map<String, Object> s = db.sql("SELECT s.user_id, s.pot_id, s.amount_paise, p.name FROM sweeps s JOIN pots p ON p.id = s.pot_id WHERE s.id = ?")
                .param(sweepId).query().singleRow();
        UUID user = (UUID) s.get("user_id");
        UUID pot = (UUID) s.get("pot_id");
        long amount = ((Number) s.get("amount_paise")).longValue();
        Reply r = up.debit(user, amount, "roundups:" + sweepId, "Round-ups into " + s.get("name"));
        Instant now = clock.instant();
        String status = r.ok() ? r.body().path("status").asText() : "FAILED";
        if (status.equals("COMPLETED")) {
            tx.executeWithoutResult(t -> {
                if (db.sql("UPDATE sweeps SET status = 'DONE', updated_at = ? WHERE id = ? AND status = 'PENDING'")
                        .params(Timestamp.from(now), sweepId).update() == 1) {
                    db.sql("INSERT INTO movements (id, pot_id, user_id, kind, amount_paise, status, at, updated_at) "
                                    + "VALUES (?, ?, ?, 'ROUND_UPS', ?, 'DONE', ?, ?)")
                            .params(UUID.randomUUID(), pot, user, amount, Timestamp.from(now), Timestamp.from(now)).update();
                }
            });
            log.info("Swept ₹{} of round-ups into pot {}", Money.rupees(amount), pot);
        } else {
            String reason = r.ok() ? r.body().path("failureReason").asText("AutoPay refused the debit.")
                    : "Payments refused the debit (" + r.status() + ").";
            // the round-ups wait for the next sweep, after retry-after
            tx.executeWithoutResult(t -> {
                db.sql("UPDATE sweeps SET status = 'FAILED', reason = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")
                        .params(reason, Timestamp.from(now), sweepId).update();
                db.sql("UPDATE round_ups SET sweep_id = NULL WHERE sweep_id = ?").param(sweepId).update();
            });
            log.info("Round-up sweep {} refused: {}", sweepId, reason);
        }
    }

    // ── buying pots' shares ──────────────────────────────────────────────────

    /** Buys whose order hadn't finished: ask the order service again (same key, it answers with the order's state). */
    void settleBuys() {
        List<Map<String, Object>> pending = db.sql("""
                        SELECT m.id, m.user_id, m.pot_id, m.quantity, p.symbol FROM movements m JOIN pots p ON p.id = m.pot_id
                        WHERE m.kind = 'PURCHASE' AND m.status = 'PENDING' ORDER BY m.at LIMIT 50""").query().listOfRows();
        for (Map<String, Object> m : pending) {
            UUID id = (UUID) m.get("id");
            apply(id, up.buyFor((UUID) m.get("user_id"), "goal:" + id, (String) m.get("symbol"), ((Number) m.get("quantity")).longValue(),
                    "goal:" + m.get("pot_id")));
        }
    }

    /** While the market is open: each pot buys as many whole shares as what it holds uninvested covers. */
    void buy() {
        if (!up.marketOpen()) {
            return;
        }
        List<Pot> pots = db.sql(Goals.POT_SQL + " WHERE status <> 'CLOSED' ORDER BY created_at").query(Goals::pot).list();
        if (pots.isEmpty()) {
            return;
        }
        Map<String, Long> prices = up.lastPrices(pots.stream().map(Pot::symbol).distinct().toList());
        Instant now = clock.instant();
        for (Pot p : pots) {
            Long price = prices.get(p.symbol());
            if (price == null || price <= 0) {
                continue;
            }
            Goals.Holding h = goals.holding(p, price);
            goals.checkReached(p, h);
            long quantity = (long) Math.floor(h.uninvested() / (price * CHARGES_ROOM));
            boolean waiting = db.sql("""
                            SELECT 1 FROM movements WHERE pot_id = ? AND kind = 'PURCHASE'
                              AND (status = 'PENDING' OR (status = 'FAILED' AND updated_at > ?)) LIMIT 1""")
                    .params(p.id(), Timestamp.from(now.minus(props.retryAfter()))).query(Integer.class).optional().isPresent();
            if (quantity < 1 || waiting) {
                continue;
            }
            // the buy is recorded (with what it should cost) before the order is asked for, so it is never placed twice
            UUID id = UUID.randomUUID();
            long estimate = (long) Math.ceil(quantity * price * CHARGES_ROOM);
            db.sql("INSERT INTO movements (id, pot_id, user_id, kind, amount_paise, quantity, price_paise, status, at, updated_at) "
                            + "VALUES (?, ?, ?, 'PURCHASE', ?, ?, ?, 'PENDING', ?, ?)")
                    .params(id, p.id(), p.userId(), Math.min(estimate, h.uninvested()), quantity, price, Timestamp.from(now), Timestamp.from(now))
                    .update();
            try {
                apply(id, up.buyFor(p.userId(), "goal:" + id, p.symbol(), quantity, "goal:" + p.id()));
            } catch (Unreachable e) {
                log.info("Buy {} for pot {} not confirmed yet: {}", id, p.id(), e.getMessage());
            }
        }
    }

    /** What the order service said about a buy. */
    private void apply(UUID id, Reply r) {
        Instant now = clock.instant();
        if (!r.ok()) {
            fail(id, "The order couldn't be placed (" + (r.body() == null ? r.status() : r.body().path("code").asText(String.valueOf(r.status()))) + ").", now);
            return;
        }
        JsonNode o = r.body();
        UUID orderId = UUID.fromString(o.path("id").asText());
        switch (o.path("status").asText()) {
            case "FILLED" -> {
                long value = Money.paise(o.path("value").asText());
                long charges = Money.paise(o.path("charges").path("total").asText("0"));
                db.sql("UPDATE movements SET status = 'DONE', order_id = ?, amount_paise = ?, price_paise = ?, quantity = ?, updated_at = ? "
                                + "WHERE id = ? AND status = 'PENDING'")
                        .params(orderId, value + charges, Money.paise(o.path("price").asText()), o.path("filledQuantity").asLong(o.path("quantity").asLong()),
                                Timestamp.from(now), id)
                        .update();
            }
            case "REJECTED", "CANCELLED", "EXPIRED" -> fail(id, o.path("rejection").path("message").asText("The order didn't execute."), now);
            default -> db.sql("UPDATE movements SET order_id = ?, updated_at = ? WHERE id = ?").params(orderId, Timestamp.from(now), id).update();
        }
    }

    private void fail(UUID id, String reason, Instant now) {
        db.sql("UPDATE movements SET status = 'FAILED', reason = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(reason, Timestamp.from(now), id).update();
    }
}
