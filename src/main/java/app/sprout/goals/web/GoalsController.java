package app.sprout.goals.web;

import app.sprout.goals.config.GoalsProperties;
import app.sprout.goals.domain.ApiException;
import app.sprout.goals.domain.ErrorCode;
import app.sprout.goals.domain.Goals;
import app.sprout.goals.domain.Goals.Contributed;
import app.sprout.goals.domain.Goals.Holding;
import app.sprout.goals.domain.Goals.Movement;
import app.sprout.goals.domain.Goals.Pot;
import app.sprout.goals.domain.Goals.Settings;
import app.sprout.goals.domain.Money;
import app.sprout.goals.domain.Upstreams;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The goals API (goals-v1.yaml). Customers through the gateway (X-User-Id); Sprout services on /internal. */
@RestController
public class GoalsController {

    public record PotRequest(String name, String target, String targetDate, String symbol) {}

    public record AmountRequest(String amount) {}

    public record RoundUpsRequest(Boolean enabled, Integer roundTo, Integer multiplier, String potId) {}

    private final Goals goals;
    private final Upstreams up;
    private final GoalsProperties props;

    public GoalsController(Goals goals, Upstreams up, GoalsProperties props) {
        this.goals = goals;
        this.up = up;
        this.props = props;
    }

    // ── pots ─────────────────────────────────────────────────────────────────

    @GetMapping("/v1/pots")
    public Map<String, Object> pots(@RequestHeader(value = "X-User-Id", required = false) String user) {
        List<Pot> pots = goals.pots(userId(user));
        Map<String, Long> prices = prices(pots);
        return Map.of("pots", pots.stream().map(p -> pot(p, goals.holding(p, prices.get(p.symbol())))).toList());
    }

    @PostMapping("/v1/pots")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody PotRequest req) {
        LocalDate date;
        try {
            date = req.targetDate() == null ? null : LocalDate.parse(req.targetDate());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "targetDate is a date like 2027-03-31.");
        }
        Goals.Started s = goals.create(userId(user), key, req.name(), req.target(), date, req.symbol());
        Pot p = s.pot();
        Map<String, Object> body = pot(p, goals.holding(p, s.created() ? null : prices(List.of(p)).get(p.symbol())));
        return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/v1/pots/{id}")
    public Map<String, Object> pot(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        Pot p = goals.pot(userId(user), id);
        Map<String, Object> m = pot(p, goals.holding(p, prices(List.of(p)).get(p.symbol())));
        m.put("movements", goals.movements(p.id()).stream().map(GoalsController::movement).toList());
        return m;
    }

    @PostMapping("/v1/pots/{id}/contributions")
    public ResponseEntity<Map<String, Object>> contribute(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          @PathVariable UUID id, @RequestBody AmountRequest req) {
        Contributed c = goals.contribute(userId(user), id, key, req.amount());
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(movement(c.movement()));
    }

    @PostMapping("/v1/pots/{id}/close")
    public Map<String, Object> close(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        Pot p = goals.close(userId(user), id);
        return pot(p, goals.holding(p, prices(List.of(p)).get(p.symbol())));
    }

    // ── round-ups ────────────────────────────────────────────────────────────

    @GetMapping("/v1/round-ups")
    public Map<String, Object> roundUps(@RequestHeader(value = "X-User-Id", required = false) String user) {
        UUID me = userId(user);
        return roundUps(me, goals.settings(me));
    }

    @PutMapping("/v1/round-ups")
    public Map<String, Object> setRoundUps(@RequestHeader(value = "X-User-Id", required = false) String user,
                                           @RequestBody RoundUpsRequest req) {
        UUID me = userId(user);
        if (req.enabled() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Say whether round-ups are on (enabled).");
        }
        UUID pot;
        try {
            pot = req.potId() == null ? null : UUID.fromString(req.potId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "potId is a pot's id.");
        }
        return roundUps(me, goals.configure(me, req.enabled(), req.roundTo(), req.multiplier(), pot));
    }

    private Map<String, Object> roundUps(UUID me, Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("roundTo", s.roundTo());
        m.put("multiplier", s.multiplier());
        if (s.potId() != null) {
            m.put("potId", s.potId().toString());
        }
        try {
            m.put("autoPay", up.autoPay(me));
        } catch (Upstreams.Unreachable e) {
            throw Upstreams.unavailable();
        }
        m.put("waiting", Money.rupees(goals.waiting(me)));
        m.put("sweepAt", props.sweepAt());
        m.put("swept", Money.rupees(goals.swept(me)));
        m.put("recent", goals.recentRoundUps(me).stream().map(r -> Map.of("spendId", r.spendId().toString(), "spent", Money.rupees(r.spent()),
                "payeeName", r.payeeName(), "amount", Money.rupees(r.amount()), "status", r.swept() ? "SWEPT" : "WAITING",
                "at", r.at().toString())).toList());
        return m;
    }

    // ── for other services ───────────────────────────────────────────────────

    @GetMapping("/internal/v1/summary")
    public Map<String, Object> summary(@RequestHeader(value = "X-Service-Key", required = false) String key, @RequestParam UUID userId) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.serviceKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only Sprout services can call this.");
        }
        return goals.summary(userId);
    }

    // ── shapes ───────────────────────────────────────────────────────────────

    private Map<String, Long> prices(List<Pot> pots) {
        try {
            return up.lastPrices(pots.stream().map(Pot::symbol).distinct().toList());
        } catch (Upstreams.Unreachable e) {
            return Map.of();   // shown at cost until prices are back
        }
    }

    static Map<String, Object> pot(Pot p, Holding h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id().toString());
        m.put("name", p.name());
        m.put("symbol", p.symbol());
        m.put("target", Money.rupees(p.target()));
        if (p.targetDate() != null) {
            m.put("targetDate", p.targetDate().toString());
        }
        m.put("status", h.progressPercent() >= 100 && p.status().equals("OPEN") ? "REACHED" : p.status());
        m.put("saved", Money.rupees(h.saved()));
        m.put("invested", Money.rupees(h.invested()));
        m.put("quantity", h.quantity());
        m.put("value", Money.rupees(h.value()));
        m.put("uninvested", Money.rupees(h.uninvested()));
        m.put("progressPercent", h.progressPercent());
        if (h.monthlyNeeded() != null) {
            m.put("monthlyNeeded", Money.rupees(h.monthlyNeeded()));
        }
        m.put("createdAt", p.createdAt().toString());
        if (p.reachedAt() != null) {
            m.put("reachedAt", p.reachedAt().toString());
        }
        return m;
    }

    static Map<String, Object> movement(Movement mv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mv.id().toString());
        m.put("kind", mv.kind());
        m.put("amount", Money.rupees(mv.amount()));
        if (mv.quantity() != null) {
            m.put("quantity", mv.quantity());
        }
        if (mv.price() != null) {
            m.put("price", Money.rupees(mv.price()));
        }
        if (mv.orderId() != null) {
            m.put("orderId", mv.orderId().toString());
        }
        m.put("status", mv.status());
        if (mv.reason() != null) {
            m.put("reason", mv.reason());
        }
        m.put("at", mv.at().toString());
        return m;
    }

    private static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
        }
    }
}
