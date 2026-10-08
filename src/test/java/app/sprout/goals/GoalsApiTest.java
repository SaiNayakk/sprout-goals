package app.sprout.goals;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.goals.domain.Engine;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Goals on a real Postgres against stand-ins for the services it calls; every response checked against goals-v1.yaml. */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=goals", "sprout.goals.every=1h"})   // the tests run the engine's rounds
@AutoConfigureMockMvc
class GoalsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SERVICE_KEY = "dev-only-service-key";

    // the stand-ins' state
    static final Map<String, Long> CASH = new ConcurrentHashMap<>();                 // user -> paise
    static final Map<String, String> AUTOPAY = new ConcurrentHashMap<>();            // user -> status
    static final AtomicBoolean MARKET_OPEN = new AtomicBoolean(true);
    static final Map<String, Long> PRICES = new ConcurrentHashMap<>(Map.of("SAPLING", 145000L, "KOSHA", 21500L));
    static final Map<String, JsonNode> ORDERS = new ConcurrentHashMap<>();           // idempotency key -> order
    static final AtomicReference<String> ORDER_OUTCOME = new AtomicReference<>("FILLED");
    static final List<Map<String, Object>> SPENDS = new CopyOnWriteArrayList<>();
    static final List<String[]> DEBITS = new CopyOnWriteArrayList<>();               // {user, reference} asked for, in order
    static final AtomicReference<String> DEBIT_OUTCOME = new AtomicReference<>("COMPLETED");
    static final AtomicInteger DEBIT_503S = new AtomicInteger();
    static final HttpServer UPSTREAMS = upstreams();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + UPSTREAMS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=goals");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        for (String s : new String[] {"marketdata", "oms", "accounts", "payments"}) {
            r.add("sprout.goals." + s + "-url", () -> base);
        }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T05:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.GOALS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired Engine engine;

    UUID user;

    @BeforeEach
    void customer() {
        user = UUID.randomUUID();
        CASH.put(user.toString(), 2_000_000L);    // ₹20,000
        AUTOPAY.put(user.toString(), "ACTIVE");
        MARKET_OPEN.set(true);
        ORDER_OUTCOME.set("FILLED");
        DEBIT_OUTCOME.set("COMPLETED");
        DEBIT_503S.set(0);
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions as(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("X-User-Id", user.toString());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode pot(String name, String target, String symbol) throws Exception {
        return body(as(post("/v1/pots"), Map.of("name", name, "target", target, "symbol", symbol, "targetDate", "2027-04-01"))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
    }

    ResultActions contribute(JsonNode pot, String amount, String key) throws Exception {
        return as(post("/v1/pots/" + pot.path("id").asText() + "/contributions").header("Idempotency-Key", key), Map.of("amount", amount));
    }

    JsonNode show(JsonNode pot) throws Exception {
        return body(as(get("/v1/pots/" + pot.path("id").asText()), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
    }

    /** The debits asked for this test's customer (earlier tests' customers may be swept in the same round). */
    List<String> debits() {
        return DEBITS.stream().filter(d -> d[0].equals(user.toString())).map(d -> d[1]).toList();
    }

    List<JsonNode> orders() {
        return ORDERS.values().stream().filter(o -> o.path("userId").asText().equals(user.toString())).toList();
    }

    void spend(String amount, String payee) {
        SPENDS.add(Map.of("seq", (long) SPENDS.size() + 1, "id", UUID.randomUUID().toString(), "userId", user.toString(), "amount", amount,
                "payeeName", payee, "at", "2026-10-05T05:00:00Z"));
    }

    // ── pots ─────────────────────────────────────────────────────────────────

    @Test
    void aPotIsStartedForAShareWithATarget() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "sapling");
        assertThat(p.path("symbol").asText()).isEqualTo("SAPLING");
        assertThat(p.path("status").asText()).isEqualTo("OPEN");
        assertThat(p.path("progressPercent").asInt()).isZero();
        assertThat(p.path("monthlyNeeded").asText()).as("₹25,000 over the 6 months to April").isEqualTo("4166.67");
        as(post("/v1/pots"), Map.of("name", "Moon", "target", "5000", "symbol", "NOSUCH"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
        as(post("/v1/pots"), Map.of("name", "Tiny", "target", "100", "symbol", "KOSHA")).andExpect(status().isBadRequest());
        for (int i = 0; i < 4; i++) {
            pot("Pot " + i, "1000", "KOSHA");
        }
        as(post("/v1/pots"), Map.of("name", "Sixth", "target", "1000", "symbol", "KOSHA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_POTS"));
        as(get("/v1/pots"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.pots.length()").value(5));
    }

    @Test
    void aPotCreatedAgainWithTheSameKeyIsCreatedOnce() throws Exception {
        Map<String, String> req = Map.of("name", "Bike", "target", "40000", "symbol", "KOSHA");
        String key = UUID.randomUUID().toString();
        JsonNode first = body(as(post("/v1/pots").header("Idempotency-Key", key), req).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        JsonNode again = body(as(post("/v1/pots").header("Idempotency-Key", key), req).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(again.path("id").asText()).isEqualTo(first.path("id").asText());
        as(get("/v1/pots"), null).andExpect(jsonPath("$.pots.length()").value(1));
        as(post("/v1/pots").header("Idempotency-Key", UUID.randomUUID().toString()), req).andExpect(status().isCreated());
        as(get("/v1/pots"), null).andExpect(jsonPath("$.pots.length()").value(2));
        as(post("/v1/pots"), req).andExpect(status().isCreated());
        as(post("/v1/pots"), req).andExpect(status().isCreated());
        as(get("/v1/pots"), null).andExpect(jsonPath("$.pots.length()").value(4));
        as(post("/v1/pots").header("Idempotency-Key", "short"), req)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        as(get("/v1/pots"), null).andExpect(jsonPath("$.pots.length()").value(4));
    }

    @Test
    void aContributionBuysWholeSharesOfThePotsShareOnce() throws Exception {
        JsonNode p = pot("Laptop", "60000", "SAPLING");
        String key = UUID.randomUUID().toString();
        JsonNode c = body(contribute(p, "3000", key).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        contribute(p, "3000", key).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(c.path("id").asText()));
        engine.round();
        engine.round();   // a second round buys nothing more: ₹3,000 covers two shares at ₹1,450
        JsonNode shown = show(p);
        assertThat(shown.path("quantity").asInt()).isEqualTo(2);
        JsonNode buy = shown.path("movements").get(0);
        assertThat(buy.path("kind").asText()).isEqualTo("PURCHASE");
        assertThat(buy.path("status").asText()).isEqualTo("DONE");
        assertThat(buy.path("amount").asText()).as("₹2,900 of shares and ₹3.48 of charges").isEqualTo("2903.48");
        assertThat(shown.path("invested").asText()).isEqualTo("2903.48");
        assertThat(shown.path("uninvested").asText()).isEqualTo("96.52");
        assertThat(shown.path("saved").asText()).isEqualTo("3000.00");
        assertThat(orders()).hasSize(1);
        JsonNode order = orders().get(0);
        assertThat(order.path("tag").asText()).isEqualTo("goal:" + p.path("id").asText());
        assertThat(order.path("product").asText()).isEqualTo("CNC");
    }

    @Test
    void moneyAlreadySetAsideCantBeSetAsideAgain() throws Exception {
        CASH.put(user.toString(), 500_000L);   // ₹5,000
        MARKET_OPEN.set(false);                // nothing is bought, so both pots hold their money uninvested
        JsonNode a = pot("Trip", "10000", "KOSHA");
        JsonNode b = pot("Phone", "10000", "KOSHA");
        contribute(a, "4000", UUID.randomUUID().toString()).andExpect(status().isCreated());
        contribute(b, "1500", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        contribute(b, "1000", UUID.randomUUID().toString()).andExpect(status().isCreated());
        engine.round();
        assertThat(orders()).as("the market is closed: nothing bought").isEmpty();
        MARKET_OPEN.set(true);
        engine.round();
        assertThat(show(a).path("quantity").asInt()).isEqualTo(18);   // ₹4,000 at ₹215
    }

    @Test
    void aRejectedBuyLeavesTheMoneyInThePotAndWaitsBeforeTryingAgain() throws Exception {
        JsonNode p = pot("Bike", "50000", "KOSHA");
        contribute(p, "1000", UUID.randomUUID().toString()).andExpect(status().isCreated());
        ORDER_OUTCOME.set("REJECTED");
        engine.round();
        JsonNode shown = show(p);
        assertThat(shown.path("movements").get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(shown.path("uninvested").asText()).isEqualTo("1000.00");
        ORDER_OUTCOME.set("FILLED");
        engine.round();
        assertThat(show(p).path("quantity").asInt()).as("not again within the hour").isZero();
        clock.advance(java.time.Duration.ofMinutes(61));
        engine.round();
        assertThat(show(p).path("quantity").asInt()).isEqualTo(4);
    }

    @Test
    void aPotReachesItsGoal() throws Exception {
        JsonNode p = pot("Headphones", "1000", "KOSHA");
        contribute(p, "1100", UUID.randomUUID().toString()).andExpect(status().isCreated());
        engine.round();
        JsonNode shown = show(p);
        assertThat(shown.path("status").asText()).isEqualTo("REACHED");
        assertThat(shown.path("progressPercent").asInt()).isEqualTo(100);
    }

    // ── round-ups ────────────────────────────────────────────────────────────

    @Test
    void roundUpsAreSweptUnderAutoPayIntoThePotAndInvested() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "KOSHA");
        as(put("/v1/round-ups"), Map.of("enabled", true, "roundTo", 10, "multiplier", 2, "potId", p.path("id").asText()))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.autoPay").value("ACTIVE"));
        spend("46.00", "Monsoon Chai");       // ₹4 up, x2: ₹8
        spend("143.50", "Tiffin Box Kitchen"); // ₹6.50 up, x2: ₹13
        spend("100.00", "City Metro Card");    // already round: nothing
        engine.round();
        JsonNode r = body(as(get("/v1/round-ups"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(r.path("waiting").asText()).isEqualTo("21.00");
        assertThat(r.path("recent")).hasSize(2);
        assertThat(debits()).as("below ₹100: nothing swept yet").isEmpty();

        as(put("/v1/round-ups"), Map.of("enabled", true, "roundTo", 100, "multiplier", 3, "potId", p.path("id").asText()))
                .andExpect(status().isOk());
        spend("399.00", "Bookworm Books");     // ₹1 up to ₹400, x3: ₹3
        spend("701.00", "Kirana Corner");      // ₹99 up to ₹800, x3: ₹297
        engine.round();
        assertThat(debits()).hasSize(1);
        assertThat(debits().get(0)).startsWith("roundups:");
        JsonNode after = body(as(get("/v1/round-ups"), null));
        assertThat(after.path("waiting").asText()).isEqualTo("0.00");
        assertThat(after.path("swept").asText()).isEqualTo("321.00");
        assertThat(after.path("recent").findValuesAsText("status")).containsOnly("SWEPT");
        JsonNode shown = show(p);
        assertThat(shown.path("saved").asText()).isEqualTo("321.00");
        assertThat(shown.path("quantity").asInt()).as("₹321 buys one share at ₹215").isEqualTo(1);
        engine.round();
        assertThat(debits()).as("swept once").hasSize(1);
    }

    @Test
    void aRefusedSweepLeavesTheRoundUpsWaitingAndIsntRetriedForAnHour() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "KOSHA");
        as(put("/v1/round-ups"), Map.of("enabled", true, "roundTo", 100, "multiplier", 3, "potId", p.path("id").asText())).andExpect(status().isOk());
        DEBIT_OUTCOME.set("FAILED");
        spend("701.00", "Kirana Corner");
        engine.round();
        engine.round();
        assertThat(debits()).as("asked once, not every round").hasSize(1);
        assertThat(body(as(get("/v1/round-ups"), null)).path("waiting").asText()).isEqualTo("297.00");
        DEBIT_OUTCOME.set("COMPLETED");
        clock.advance(java.time.Duration.ofMinutes(61));
        engine.round();
        assertThat(debits()).hasSize(2);
        assertThat(show(p).path("saved").asText()).isEqualTo("297.00");
    }

    @Test
    void aSweepWithAnUnknownOutcomeIsAskedAgainWithTheSameReference() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "KOSHA");
        as(put("/v1/round-ups"), Map.of("enabled", true, "roundTo", 100, "multiplier", 3, "potId", p.path("id").asText())).andExpect(status().isOk());
        DEBIT_503S.set(1);
        spend("701.00", "Kirana Corner");
        engine.round();
        engine.round();
        assertThat(debits()).hasSize(2);
        assertThat(debits().get(1)).isEqualTo(debits().get(0));
        assertThat(show(p).path("saved").asText()).as("put in once").isEqualTo("297.00");
    }

    @Test
    void closingAPotStopsMoneyGoingIn() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "KOSHA");
        as(put("/v1/round-ups"), Map.of("enabled", true, "potId", p.path("id").asText())).andExpect(status().isOk());
        as(post("/v1/pots/" + p.path("id").asText() + "/close"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("CLOSED"));
        contribute(p, "500", UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POT_STATE"));
        assertThat(body(as(get("/v1/round-ups"), null)).path("enabled").asBoolean()).isFalse();
        as(put("/v1/round-ups"), Map.of("enabled", true, "potId", p.path("id").asText())).andExpect(status().isConflict());
    }

    @Test
    void otherServicesReadASummaryWithTheirKey() throws Exception {
        JsonNode p = pot("Goa trip", "25000", "KOSHA");
        contribute(p, "500", UUID.randomUUID().toString()).andExpect(status().isCreated());
        mvc.perform(get("/internal/v1/summary?userId=" + user).header("X-Service-Key", SERVICE_KEY)).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.potsOpened").value(1)).andExpect(jsonPath("$.contributions").value(1));
        mvc.perform(get("/internal/v1/summary?userId=" + user)).andExpect(status().isUnauthorized());
    }

    // ── stand-ins ────────────────────────────────────────────────────────────

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static String rupees(long p) {
        return String.format("%d.%02d", p / 100, p % 100);
    }

    /** Accounts, market data, the order service and payments, each as small as goals needs. */
    static HttpServer upstreams() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/accounts/", ex -> reply(ex, 200, Map.of("status", "ACTIVE")));
            s.createContext("/v1/instruments/", ex -> {
                String sym = ex.getRequestURI().getPath().substring("/v1/instruments/".length());
                reply(ex, PRICES.containsKey(sym) ? 200 : 404, Map.of("symbol", sym, "tradable", true));
            });
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", MARKET_OPEN.get() ? "OPEN" : "CLOSED", "sessionDate", "2026-10-05")));
            s.createContext("/v1/quotes", ex -> {
                String[] syms = ex.getRequestURI().getQuery().replace("symbols=", "").split(",");
                List<Map<String, Object>> quotes = new java.util.ArrayList<>();
                for (String sym : syms) {
                    quotes.add(Map.of("symbol", sym, "last", PRICES.get(sym) / 100.0));
                }
                reply(ex, 200, Map.of("quotes", quotes));
            });
            s.createContext("/v1/funds", ex -> reply(ex, 200, Map.of("cash", rupees(CASH.getOrDefault(ex.getRequestHeaders().getFirst("X-User-Id"), 0L)))));
            s.createContext("/internal/v1/orders", ex -> {
                String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                JsonNode req = JSON.readTree(ex.getRequestBody());
                JsonNode o = ORDERS.computeIfAbsent(key, k -> {
                    long qty = req.path("quantity").asLong();
                    long price = PRICES.get(req.path("symbol").asText());
                    long value = qty * price;
                    long charges = Math.round(value * 0.0012);
                    var node = JSON.createObjectNode();
                    node.put("id", UUID.randomUUID().toString()).put("userId", req.path("userId").asText())
                            .put("status", ORDER_OUTCOME.get()).put("tag", req.path("tag").asText())
                            .put("product", req.path("product").asText()).put("quantity", qty).put("price", rupees(price))
                            .put("value", rupees(value));
                    node.putObject("charges").put("total", rupees(charges));
                    if (ORDER_OUTCOME.get().equals("REJECTED")) {
                        node.putObject("rejection").put("code", "INSUFFICIENT_FUNDS").put("message", "Not enough money to buy this.");
                    }
                    return node;
                });
                reply(ex, 201, o);
            });
            s.createContext("/v1/mandates/me", ex -> {
                String st = AUTOPAY.get(ex.getRequestHeaders().getFirst("X-User-Id"));
                reply(ex, st == null ? 404 : 200, st == null ? Map.of("code", "NOT_FOUND") : Map.of("status", st));
            });
            s.createContext("/internal/v1/spends", ex -> {
                long after = Long.parseLong(ex.getRequestURI().getQuery().replaceAll(".*after=(\\d+).*", "$1"));
                List<Map<String, Object>> page = SPENDS.stream().filter(x -> (long) x.get("seq") > after).toList();
                reply(ex, 200, Map.of("spends", page, "next", page.isEmpty() ? after : page.get(page.size() - 1).get("seq")));
            });
            s.createContext("/internal/v1/mandate-debits", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody());
                DEBITS.add(new String[] {req.path("userId").asText(), req.path("reference").asText()});
                if (DEBIT_503S.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    reply(ex, 503, Map.of("code", "UPSTREAM_UNAVAILABLE"));
                    return;
                }
                String outcome = DEBIT_OUTCOME.get();
                reply(ex, 201, outcome.equals("COMPLETED") ? Map.of("status", "COMPLETED")
                        : Map.of("status", "FAILED", "failureReason", "No active AutoPay mandate."));
            });
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
