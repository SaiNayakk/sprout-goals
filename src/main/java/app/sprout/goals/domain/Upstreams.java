package app.sprout.goals.domain;

import app.sprout.goals.config.GoalsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * What goals need from the rest of Sprout: whether the customer has an account (accounts), prices and
 * whether the market is open (market data), their cash and buying a pot's share for them (the order
 * service), and AutoPay and the spends it shares (payments). Each call has a hard deadline.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(4);

    /** No answer, or an answer that isn't a decision (5xx). The outcome is unknown. */
    public static class Unreachable extends RuntimeException {
        public Unreachable(String what) {
            super(what);
        }
    }

    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private final GoalsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Upstreams(GoalsProperties props, ObjectMapper json, Onward onward) {
        this.props = props;
        this.json = json;
        this.onward = onward;
    }

    // ── accounts and market data ─────────────────────────────────────────────

    public void requireAccount(UUID user) {
        Reply r = send("accounts", HttpRequest.newBuilder(URI.create(props.accountsUrl() + "/internal/v1/accounts/" + user))
                .header("X-Service-Key", props.serviceKey()).GET());
        if (r.status() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        if (!r.ok()) {
            throw unavailable();
        }
    }

    public boolean tradable(String symbol) {
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/instruments/" + symbol)).GET());
        if (r.status() == 404 || r.status() == 422) {
            return false;
        }
        if (!r.ok()) {
            throw unavailable();
        }
        return r.body().path("tradable").asBoolean();
    }

    public boolean marketOpen() {
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/market")).GET());
        if (!r.ok()) {
            throw new Unreachable("market data answered " + r.status());
        }
        return "OPEN".equals(r.body().path("state").asText());
    }

    /** Last prices in paise, by symbol. */
    public Map<String, Long> lastPrices(java.util.Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/quotes?symbols="
                + String.join(",", symbols))).GET());
        if (!r.ok()) {
            throw new Unreachable("market data answered " + r.status());
        }
        Map<String, Long> prices = new java.util.HashMap<>();
        r.body().path("quotes").forEach(q -> prices.put(q.path("symbol").asText(), Math.round(q.path("last").asDouble() * 100)));
        return prices;
    }

    // ── the order service ────────────────────────────────────────────────────

    /** The customer's cash in paise (what they could trade with now). */
    public long cash(UUID user) {
        Reply r = send("the order service", HttpRequest.newBuilder(URI.create(props.omsUrl() + "/v1/funds"))
                .header("X-User-Id", user.toString()).GET());
        if (r.status() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        if (!r.ok()) {
            throw unavailable();
        }
        return Money.paise(r.body().path("cash").asText());
    }

    /** Places (or, repeated with the same key, finds) a pot's buy, on the customer's behalf. */
    public Reply buyFor(UUID user, String key, String symbol, long quantity, String tag) {
        Map<String, Object> body = Map.of("userId", user.toString(), "symbol", symbol, "side", "BUY", "quantity", quantity,
                "orderType", "MARKET", "product", "CNC", "tag", tag);
        return send("the order service", HttpRequest.newBuilder(URI.create(props.omsUrl() + "/internal/v1/orders"))
                .header("Content-Type", "application/json").header("X-Service-Key", props.serviceKey()).header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    // ── payments ─────────────────────────────────────────────────────────────

    /** The customer's AutoPay: NONE, AWAITING_APPROVAL or ACTIVE (anything that ended counts as NONE). */
    public String autoPay(UUID user) {
        Reply r = send("payments", HttpRequest.newBuilder(URI.create(props.paymentsUrl() + "/v1/mandates/me"))
                .header("X-User-Id", user.toString()).GET());
        if (r.status() == 404) {
            return "NONE";
        }
        if (!r.ok()) {
            throw unavailable();
        }
        String status = r.body().path("status").asText();
        return status.equals("ACTIVE") || status.equals("AWAITING_APPROVAL") ? status : "NONE";
    }

    public Reply spends(long after, int limit) {
        return send("payments", HttpRequest.newBuilder(URI.create(props.paymentsUrl() + "/internal/v1/spends?after=" + after + "&limit=" + limit))
                .header("X-Service-Key", props.serviceKey()).GET());
    }

    /** Debits the customer's bank under their AutoPay into their Sprout balance. 503 (unknown) is {@link Unreachable}. */
    public Reply debit(UUID user, long paise, String reference, String description) {
        Map<String, Object> body = Map.of("userId", user.toString(), "amount", Money.rupees(paise), "reference", reference,
                "description", description);
        return send("payments", HttpRequest.newBuilder(URI.create(props.paymentsUrl() + "/internal/v1/mandate-debits"))
                .header("Content-Type", "application/json").header("X-Service-Key", props.serviceKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private Reply send(String what, HttpRequest.Builder req) {
        onward.headers(req);
        try {
            // sendAsync + get: the deadline holds even while the host's name is being looked up
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode());
            }
            return new Reply(res.statusCode(), res.body() == null || res.body().isBlank() ? null : json.readTree(res.body()));
        } catch (Unreachable e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Unreachable(what + " unreachable: " + e.getClass().getSimpleName());
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout isn't reachable right now. Try again shortly.", 5, Map.of());
    }
}
