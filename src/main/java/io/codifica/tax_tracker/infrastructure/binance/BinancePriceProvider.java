package io.codifica.tax_tracker.infrastructure.binance;

import io.codifica.tax_tracker.domain.enrichment.PriceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historical EUR price provider backed by the Binance klines (candlestick) API.
 *
 * <h2>Endpoint</h2>
 * {@code GET /api/v3/klines?symbol={PAIR}&interval=1m&startTime={ms}&limit=1}
 * <p>
 * Response is a JSON array-of-arrays. Index [0][4] is the kline <em>close</em>
 * price, which is the best single-figure approximation of the spot price at
 * a given second within that minute.
 *
 * <h2>EUR price strategy (two-step)</h2>
 * <ol>
 *   <li>Try {@code {ASSET}EUR} directly — most major assets have an EUR pair
 *       on Binance (BTCEUR, ETHEUR, ADAEUR, …).</li>
 *   <li>If the pair does not exist (HTTP 400 "Invalid symbol"), fall back to
 *       {@code {ASSET}USDT} ÷ {@code EURUSDT}:
 *       <pre>
 *         assetUsdt  = klineClose("{ASSET}USDT", t)
 *         eurUsdt    = klineClose("EURUSDT", t)       // EUR expressed in USDT
 *         priceEur   = assetUsdt / eurUsdt            (scale 8, HALF_UP)
 *       </pre>
 *   </li>
 * </ol>
 *
 * <h2>Caching</h2>
 * Results are cached by {@code (pair, minute-bucket)} — klines are
 * minute-granular so the same call for any second within a minute yields
 * the identical result. Historical klines are immutable, so there is no TTL.
 *
 * <h2>Rate limiting</h2>
 * Sliding 60-second window identical to the CoinGecko provider.
 * Binance public endpoints allow ~1 200 request-weight/min; each klines
 * call costs 2 weight; we default to a conservative 60 rpm.
 *
 * <h2>No API key required</h2>
 * The klines endpoint is fully public.
 */
@Component
public class BinancePriceProvider implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(BinancePriceProvider.class);

    private static final int PRICE_SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int INTERMEDIATE_SCALE = 18;

    /** Kline response array index for the close price. */
    private static final int CLOSE_IDX = 4;

    /** Pair used to convert USDT → EUR: 1 EURUSDT = X USDT per EUR. */
    private static final String EUR_USDT_PAIR = "EURUSDT";

    private final RestClient restClient;
    private final BinancePriceProperties props;

    /** Cache: (pair, minute-bucket) → close price. */
    private final Map<CacheKey, BigDecimal> cache = new ConcurrentHashMap<>();

    /** Pairs confirmed absent on Binance (HTTP 400 "Invalid symbol"), to skip retry. */
    private final Set<String> knownMissingPairs = ConcurrentHashMap.newKeySet();

    /** Sliding-window rate limiter timestamps. */
    private final Deque<Instant> callWindow = new ArrayDeque<>();

    public BinancePriceProvider(BinancePriceProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
    }

    // -------------------------------------------------------------------------
    // PriceProvider contract
    // -------------------------------------------------------------------------

    @Override
    public Optional<BigDecimal> priceEur(String assetSymbol, Instant at) {
        // Stablecoins: skip Binance, let CoinGecko handle EUR rates
        if (assetSymbol.equals("EUR")) return Optional.of(BigDecimal.ONE.setScale(PRICE_SCALE, ROUNDING));

        // Try direct EUR pair first
        String eurPair = assetSymbol + "EUR";
        if (!knownMissingPairs.contains(eurPair)) {
            Optional<BigDecimal> direct = fetchKlineClose(eurPair, at);
            if (direct.isPresent()) return direct;
        }

        // Fall back: {ASSET}USDT ÷ EURUSDT
        return deriveViaUsdt(assetSymbol, at);
    }

    // -------------------------------------------------------------------------
    // USDT-based derivation
    // -------------------------------------------------------------------------

    private Optional<BigDecimal> deriveViaUsdt(String assetSymbol, Instant at) {
        String usdtPair = assetSymbol + "USDT";
        if (knownMissingPairs.contains(usdtPair)) {
            log.debug("No USDT pair for {} on Binance — skipping", assetSymbol);
            return Optional.empty();
        }

        Optional<BigDecimal> assetUsdt = fetchKlineClose(usdtPair, at);
        if (assetUsdt.isEmpty()) {
            log.debug("Could not fetch {} kline — skipping Binance for {}", usdtPair, assetSymbol);
            return Optional.empty();
        }

        Optional<BigDecimal> eurUsdt = fetchKlineClose(EUR_USDT_PAIR, at);
        if (eurUsdt.isEmpty()) {
            log.warn("Could not fetch EURUSDT kline at {} — cannot derive EUR price for {}",
                    at, assetSymbol);
            return Optional.empty();
        }

        // assetPriceEur = assetPriceUsdt / eurUsdtRate
        // (eurUsdtRate = how many USDT = 1 EUR, e.g. 1.08 means 1 EUR = 1.08 USDT)
        BigDecimal priceEur = assetUsdt.get()
                .divide(eurUsdt.get(), INTERMEDIATE_SCALE, ROUNDING)
                .setScale(PRICE_SCALE, ROUNDING);

        log.debug("Derived {} EUR price via USDT: {} / {} = {}",
                assetSymbol, assetUsdt.get(), eurUsdt.get(), priceEur);
        return Optional.of(priceEur);
    }

    // -------------------------------------------------------------------------
    // Kline fetch + cache
    // -------------------------------------------------------------------------

    /**
     * Fetches the 1-minute kline close price for {@code pair} at {@code at}.
     * Returns empty if the pair doesn't exist or no data is available.
     * Protected to allow overriding in tests without live HTTP calls.
     */
    protected Optional<BigDecimal> fetchKlineClose(String pair, Instant at) {
        // Bucket to the start of the minute — kline interval is 1m
        Instant bucket = at.truncatedTo(ChronoUnit.MINUTES);
        CacheKey key = new CacheKey(pair, bucket);

        BigDecimal cached = cache.get(key);
        if (cached != null) return Optional.of(cached);

        return fetchFromApi(pair, bucket, key);
    }

    private Optional<BigDecimal> fetchFromApi(String pair, Instant bucket, CacheKey key) {
        throttle();
        long startTimeMs = bucket.toEpochMilli();

        try {
            // Binance klines: array-of-arrays; each inner array has 12 elements.
            // Index 4 is the close price as a string.
            List<List<Object>> response = restClient.get()
                    .uri("/api/v3/klines?symbol={pair}&interval=1m&startTime={start}&limit=1",
                            pair, startTimeMs)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || response.isEmpty()) {
                log.debug("Binance returned empty klines for {} at {}", pair, bucket);
                return Optional.empty();
            }

            String closeStr = response.get(0).get(CLOSE_IDX).toString();
            BigDecimal close = new BigDecimal(closeStr).setScale(PRICE_SCALE, ROUNDING);
            cache.put(key, close);
            log.debug("Fetched Binance kline close for {} at {}: {}", pair, bucket, close);
            return Optional.of(close);

        } catch (HttpClientErrorException.BadRequest ex) {
            // 400 = "Invalid symbol" — pair doesn't exist on Binance
            knownMissingPairs.add(pair);
            log.debug("Binance pair {} does not exist (HTTP 400) — will not retry", pair);
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Binance kline fetch failed for {} at {}: {}", pair, bucket, ex.getMessage());
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Rate limiter — sliding 60-second window
    // -------------------------------------------------------------------------

    private synchronized void throttle() {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(60);
        while (!callWindow.isEmpty() && callWindow.peekFirst().isBefore(windowStart)) {
            callWindow.pollFirst();
        }
        if (callWindow.size() >= props.getRequestsPerMinute()) {
            long waitMillis =
                    java.time.Duration.between(now,
                            callWindow.peekFirst().plusSeconds(60)).toMillis() + 50;
            if (waitMillis > 0) {
                log.debug("Binance rate limit: waiting {}ms", waitMillis);
                try { Thread.sleep(waitMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        callWindow.addLast(Instant.now());
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private record CacheKey(String pair, Instant bucket) {}
}


