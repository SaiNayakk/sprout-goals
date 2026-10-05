package app.sprout.goals.domain;

import app.sprout.goals.config.GoalsProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Pots and round-up settings, and what a pot holds. A pot's money is the customer's own Sprout cash,
 * set aside for it: what went in less what its buys cost is what it still holds uninvested. Its buys
 * and the round-up sweeps are made by {@link Engine}.
 */
@Service
public class Goals {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<Integer> ROUND_TO = Set.of(10, 50, 100);

    public record Pot(UUID id, UUID userId, String name, String symbol, long target, LocalDate targetDate, String status,
                      Instant createdAt, Instant reachedAt) {}

    public record Movement(UUID id, UUID potId, String kind, long amount, Long quantity, Long price, UUID orderId, String status,
                           String reason, Instant at) {}

    /** What a pot holds now: put in, spent on its share, shares, their value, and what is left uninvested. */
    public record Holding(long saved, long invested, long quantity, long value, long uninvested, int progressPercent, Long monthlyNeeded) {}

    public record Settings(boolean enabled, int roundTo, int multiplier, UUID potId) {}

    public record RoundUp(UUID spendId, long spent, String payeeName, long amount, boolean swept, Instant at) {}

    /** A contribution, and whether this call made it (or found it from an earlier call with the same key). */
    public record Contributed(Movement movement, boolean created) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;
    private final GoalsProperties props;

    public Goals(JdbcClient db, Clock clock, Upstreams up, GoalsProperties props) {
        this.db = db;
        this.clock = clock;
        this.up = up;
        this.props = props;
    }

    // ── pots ─────────────────────────────────────────────────────────────────

    public Pot create(UUID user, String name, String target, LocalDate targetDate, String symbol) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 40) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the pot a name (1 to 40 characters).");
        }
        long paise = Money.paise(target);
        if (paise < Money.paise("500.00") || paise > Money.paise("10000000.00")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A target is between ₹500 and ₹1,00,00,000.");
        }
        if (targetDate != null && !targetDate.isAfter(LocalDate.now(clock.withZone(IST)))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The target date must be in the future.");
        }
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        up.requireAccount(user);
        if (sym.isEmpty() || !up.tradable(sym)) {
            throw new ApiException(ErrorCode.UNKNOWN_INSTRUMENT, "There's no share called " + symbol + " to invest in.");
        }
        int open = db.sql("SELECT COUNT(*) FROM pots WHERE user_id = ? AND status <> 'CLOSED'").param(user).query(Integer.class).single();
        if (open >= props.maxOpen()) {
            throw new ApiException(ErrorCode.TOO_MANY_POTS, "You have " + open + " open pots, the most there can be. Close one first.");
        }
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO pots (id, user_id, name, symbol, target_paise, target_date, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)")
                .params(id, user, n, sym, paise, targetDate == null ? null : java.sql.Date.valueOf(targetDate), ts(clock.instant())).update();
        return pot(user, id);
    }

    public List<Pot> pots(UUID user) {
        return db.sql(POT_SQL + " WHERE user_id = ? ORDER BY created_at DESC").param(user).query(Goals::pot).list();
    }

    public Pot pot(UUID user, UUID id) {
        return db.sql(POT_SQL + " WHERE id = ? AND user_id = ?").params(id, user).query(Goals::pot).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such pot of yours."));
    }

    public List<Movement> movements(UUID potId) {
        return db.sql(MOVEMENT_SQL + " WHERE pot_id = ? ORDER BY seq DESC LIMIT 200").param(potId).query(Goals::movement).list();
    }

    public Pot close(UUID user, UUID id) {
        Pot p = pot(user, id);
        if (!p.status().equals("CLOSED")) {
            db.sql("UPDATE pots SET status = 'CLOSED', closed_at = ? WHERE id = ?").params(ts(clock.instant()), id).update();
            // round-ups can't go into a closed pot
            db.sql("UPDATE round_up_settings SET enabled = false, updated_at = ? WHERE user_id = ? AND pot_id = ?")
                    .params(ts(clock.instant()), user, id).update();
        }
        return pot(user, id);
    }

    /** Sets money from the Sprout balance aside for a pot. Its share is bought by the engine (at once if the market is open). */
    public Contributed contribute(UUID user, UUID potId, String key, String amount) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
        long paise = Money.paise(amount);
        if (paise < Money.paise(props.minimum()) || paise > Money.paise(props.maximum())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each contribution is between ₹" + props.minimum() + " and ₹" + props.maximum() + ".");
        }
        Optional<Movement> earlier = byKey(user, key);
        if (earlier.isPresent()) {
            return new Contributed(earlier.get(), false);
        }
        Pot p = pot(user, potId);
        if (p.status().equals("CLOSED")) {
            throw new ApiException(ErrorCode.POT_STATE, "This pot is closed.");
        }
        // the cash a customer has is theirs to set aside once: what other pots hold uninvested is already spoken for
        long free = up.cash(user) - uninvestedEverywhere(user);
        if (paise > free) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "That's more than you have free to put in (₹" + Money.rupees(Math.max(0, free)) + ").");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            db.sql("INSERT INTO movements (id, pot_id, user_id, kind, idempotency_key, amount_paise, status, at, updated_at) "
                    + "VALUES (?, ?, ?, 'CONTRIBUTION', ?, ?, 'DONE', ?, ?)").params(id, potId, user, key, paise, ts(now), ts(now)).update();
        } catch (DuplicateKeyException e) {
            return new Contributed(byKey(user, key).orElseThrow(), false);
        }
        return new Contributed(movement(id), true);
    }

    // ── what a pot holds ─────────────────────────────────────────────────────

    public Holding holding(Pot p, Long lastPrice) {
        long saved = sum(p.id(), "kind IN ('CONTRIBUTION', 'ROUND_UPS') AND status = 'DONE'", "amount_paise");
        long invested = sum(p.id(), "kind = 'PURCHASE' AND status = 'DONE'", "amount_paise");
        long buying = sum(p.id(), "kind = 'PURCHASE' AND status = 'PENDING'", "amount_paise");
        long quantity = sum(p.id(), "kind = 'PURCHASE' AND status = 'DONE'", "quantity");
        long value = lastPrice == null ? invested : quantity * lastPrice;
        long uninvested = Math.max(0, saved - invested - buying);
        long have = value + uninvested + buying;
        int progress = (int) Math.min(100, have * 100 / p.target());
        Long monthly = null;
        if (p.targetDate() != null && !p.status().equals("CLOSED")) {
            LocalDate today = LocalDate.now(clock.withZone(IST));
            long months = Math.max(1, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), p.targetDate().withDayOfMonth(1)));
            monthly = Math.max(0, (p.target() - have + months - 1) / months);
        }
        return new Holding(saved, invested, quantity, value, uninvested, progress, monthly);
    }

    /** Marks a pot REACHED the first time what it holds meets its target. */
    public void checkReached(Pot p, Holding h) {
        if (p.status().equals("OPEN") && h.progressPercent() >= 100) {
            db.sql("UPDATE pots SET status = 'REACHED', reached_at = ? WHERE id = ? AND status = 'OPEN'").params(ts(clock.instant()), p.id()).update();
        }
    }

    long uninvestedEverywhere(UUID user) {
        return db.sql("""
                        SELECT COALESCE(SUM(CASE WHEN m.kind IN ('CONTRIBUTION', 'ROUND_UPS') AND m.status = 'DONE' THEN m.amount_paise
                                                 WHEN m.kind = 'PURCHASE' AND m.status IN ('DONE', 'PENDING') THEN -m.amount_paise ELSE 0 END), 0)
                        FROM movements m JOIN pots p ON p.id = m.pot_id WHERE m.user_id = ? AND p.status <> 'CLOSED'""")
                .param(user).query(Long.class).single();
    }

    private long sum(UUID pot, String where, String column) {
        return db.sql("SELECT COALESCE(SUM(" + column + "), 0) FROM movements WHERE pot_id = ? AND " + where).param(pot).query(Long.class).single();
    }

    // ── round-ups ────────────────────────────────────────────────────────────

    public Settings settings(UUID user) {
        return db.sql("SELECT enabled, round_to, multiplier, pot_id FROM round_up_settings WHERE user_id = ?").param(user)
                .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, UUID.class))).optional()
                .orElse(new Settings(false, 10, 1, null));
    }

    public Settings configure(UUID user, boolean enabled, Integer roundTo, Integer multiplier, UUID potId) {
        int to = roundTo == null ? 10 : roundTo;
        int times = multiplier == null ? 1 : multiplier;
        if (!ROUND_TO.contains(to) || times < 1 || times > 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Round up to ₹10, ₹50 or ₹100, one to three times over.");
        }
        if (enabled) {
            if (potId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose the pot round-ups go into.");
            }
            if (pot(user, potId).status().equals("CLOSED")) {
                throw new ApiException(ErrorCode.POT_STATE, "That pot is closed.");
            }
        } else if (potId != null) {
            pot(user, potId);
        }
        db.sql("""
                        INSERT INTO round_up_settings (user_id, enabled, round_to, multiplier, pot_id, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET enabled = EXCLUDED.enabled, round_to = EXCLUDED.round_to,
                            multiplier = EXCLUDED.multiplier, pot_id = COALESCE(EXCLUDED.pot_id, round_up_settings.pot_id),
                            updated_at = EXCLUDED.updated_at""")
                .params(user, enabled, to, times, potId, ts(clock.instant())).update();
        return settings(user);
    }

    public long waiting(UUID user) {
        return db.sql("SELECT COALESCE(SUM(amount_paise), 0) FROM round_ups WHERE user_id = ? AND sweep_id IS NULL").param(user)
                .query(Long.class).single();
    }

    public long swept(UUID user) {
        return db.sql("SELECT COALESCE(SUM(amount_paise), 0) FROM sweeps WHERE user_id = ? AND status = 'DONE'").param(user).query(Long.class).single();
    }

    public List<RoundUp> recentRoundUps(UUID user) {
        return db.sql("""
                        SELECT r.spend_id, r.spent_paise, r.payee_name, r.amount_paise, (s.status = 'DONE') AS swept, r.at
                        FROM round_ups r LEFT JOIN sweeps s ON s.id = r.sweep_id
                        WHERE r.user_id = ? ORDER BY r.at DESC LIMIT 20""")
                .param(user)
                .query((rs, n) -> new RoundUp(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5),
                        rs.getTimestamp(6).toInstant()))
                .list();
    }

    /** A spend rounded up: to the next multiple, times the multiplier. A round amount rounds up by nothing. */
    static long roundUp(long spentPaise, int roundTo, int multiplier) {
        long step = roundTo * 100L;
        long rest = spentPaise % step;
        return rest == 0 ? 0 : (step - rest) * multiplier;
    }

    // ── for other services ───────────────────────────────────────────────────

    public Map<String, Object> summary(UUID user) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("userId", user.toString());
        m.put("potsOpened", db.sql("SELECT COUNT(*) FROM pots WHERE user_id = ?").param(user).query(Integer.class).single());
        m.put("potsReached", db.sql("SELECT COUNT(*) FROM pots WHERE user_id = ? AND reached_at IS NOT NULL").param(user).query(Integer.class).single());
        m.put("roundUps", db.sql("SELECT COUNT(*) FROM round_ups WHERE user_id = ?").param(user).query(Integer.class).single());
        m.put("roundedUp", Money.rupees(db.sql("SELECT COALESCE(SUM(amount_paise), 0) FROM round_ups WHERE user_id = ?").param(user)
                .query(Long.class).single()));
        m.put("contributions", db.sql("SELECT COUNT(*) FROM movements WHERE user_id = ? AND kind = 'CONTRIBUTION' AND status = 'DONE'")
                .param(user).query(Integer.class).single());
        db.sql("SELECT MIN(at) FROM round_ups WHERE user_id = ?").param(user).query(Timestamp.class).optional()
                .filter(t -> t != null).ifPresent(t -> m.put("firstRoundUpAt", t.toInstant().toString()));
        return m;
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private Optional<Movement> byKey(UUID user, String key) {
        return db.sql(MOVEMENT_SQL + " WHERE user_id = ? AND idempotency_key = ?").params(user, key).query(Goals::movement).optional();
    }

    Movement movement(UUID id) {
        return db.sql(MOVEMENT_SQL + " WHERE id = ?").param(id).query(Goals::movement).single();
    }

    static Map<UUID, String> symbols(List<Pot> pots) {
        return pots.stream().collect(Collectors.toMap(Pot::id, Pot::symbol));
    }

    static final String POT_SQL = "SELECT id, user_id, name, symbol, target_paise, target_date, status, created_at, reached_at FROM pots";
    static final String MOVEMENT_SQL =
            "SELECT id, pot_id, kind, amount_paise, quantity, price_paise, order_id, status, reason, at FROM movements";

    static Pot pot(ResultSet rs, int n) throws SQLException {
        java.sql.Date date = rs.getDate("target_date");
        Timestamp reached = rs.getTimestamp("reached_at");
        return new Pot(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"), rs.getString("symbol"),
                rs.getLong("target_paise"), date == null ? null : date.toLocalDate(), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), reached == null ? null : reached.toInstant());
    }

    static Movement movement(ResultSet rs, int n) throws SQLException {
        return new Movement(rs.getObject("id", UUID.class), rs.getObject("pot_id", UUID.class), rs.getString("kind"), rs.getLong("amount_paise"),
                (Long) rs.getObject("quantity"), (Long) rs.getObject("price_paise"), rs.getObject("order_id", UUID.class), rs.getString("status"),
                rs.getString("reason"), rs.getTimestamp("at").toInstant());
    }

    static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
