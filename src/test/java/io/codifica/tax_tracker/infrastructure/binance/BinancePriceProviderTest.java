package io.codifica.tax_tracker.infrastructure.binance;

import io.codifica.tax_tracker.infrastructure.binance.BinancePriceProperties;
import io.codifica.tax_tracker.infrastructure.binance.BinancePriceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BinancePriceProvider} without live network calls.
 *
 * We test the price-derivation and routing logic by subclassing
 * {@link BinancePriceProvider} and overriding the internal
 * {@code fetchKlineCloseForTest} hook with a stub map of pair→price.
 *
 * The integration with the actual Binance REST API is covered by manual
 * smoke tests or a separate {@code @SpringBootTest} with WireMock.
 */
@DisplayName("BinancePriceProvider")
class BinancePriceProviderTest {

    private static final Instant T = Instant.parse("2021-10-24T14:17:05Z");

    // -------------------------------------------------------------------------
    // Testable subclass
    // -------------------------------------------------------------------------

    /**
     * Subclass that replaces the HTTP call with a simple map lookup.
     * Key: Binance pair symbol (e.g. "BTCEUR"), Value: close price.
     */
    static class StubBinancePriceProvider extends BinancePriceProvider {

        private final Map<String, BigDecimal> stubPrices;

        StubBinancePriceProvider(Map<String, BigDecimal> stubPrices) {
            super(defaultProps());
            this.stubPrices = stubPrices;
        }

        @Override
        protected Optional<BigDecimal> fetchKlineClose(String pair, Instant at) {
            return Optional.ofNullable(stubPrices.get(pair));
        }

        private static BinancePriceProperties defaultProps() {
            var p = new BinancePriceProperties();
            p.setBaseUrl("http://stub");
            p.setRequestsPerMinute(1000);
            return p;
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Returns EUR price directly when {ASSET}EUR pair exists")
    void returnsDirectEurPrice() {
        var provider = new StubBinancePriceProvider(Map.of(
                "BTCEUR", new BigDecimal("27750.00000000")
        ));

        Optional<BigDecimal> result = provider.priceEur("BTC", T);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("27750.00000000").compareTo(result.get()));
    }

    @Test
    @DisplayName("Falls back to USDT derivation when EUR pair absent")
    void derivesViaUsdtWhenEurPairAbsent() {
        // No AXSEUR pair; has AXSUSDT and EURUSDT
        var provider = new StubBinancePriceProvider(Map.of(
                "AXSUSDT",  new BigDecimal("69.00000000"),  // 69 USDT per AXS
                "EURUSDT",  new BigDecimal("1.08000000")    // 1 EUR = 1.08 USDT
        ));

        // Expected: 69 / 1.08 = 63.88888888... → scale 8 HALF_UP = 63.88888889
        Optional<BigDecimal> result = provider.priceEur("AXS", T);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("63.88888889").compareTo(result.get()));
    }

    @Test
    @DisplayName("Returns empty when both EUR and USDT pairs absent")
    void returnsEmptyWhenBothPairsAbsent() {
        var provider = new StubBinancePriceProvider(Map.of(
                "EURUSDT", new BigDecimal("1.08")  // only EURUSDT, no asset pairs
        ));
        assertTrue(provider.priceEur("OBSCURECOIN", T).isEmpty());
    }

    @Test
    @DisplayName("Returns empty when EURUSDT pair absent (cannot derive)")
    void returnsEmptyWhenEurUsdtAbsent() {
        var provider = new StubBinancePriceProvider(Map.of(
                "AXSUSDT", new BigDecimal("69.00")  // has USDT pair but no EURUSDT
        ));
        assertTrue(provider.priceEur("AXS", T).isEmpty());
    }

    @Test
    @DisplayName("EUR asset returns 1.0 directly without API call")
    void eurAssetReturnsOne() {
        var provider = new StubBinancePriceProvider(Map.of());
        Optional<BigDecimal> result = provider.priceEur("EUR", T);
        assertTrue(result.isPresent());
        assertEquals(0, BigDecimal.ONE.compareTo(result.get()));
    }

    @Test
    @DisplayName("USDT derivation math: scale 8 HALF_UP applied only at final step")
    void usdtDerivationMath() {
        // 1 BTC = 30,000.33 USDT; 1 EUR = 1.07 USDT
        // Expected: 30000.33 / 1.07 = 28037.69158878...  → HALF_UP scale 8 = 28037.69158879
        var provider = new StubBinancePriceProvider(Map.of(
                "BTCUSDT",  new BigDecimal("30000.33000000"),
                "EURUSDT",  new BigDecimal("1.07000000")
        ));

        // No BTCEUR so derivation path is used
        Optional<BigDecimal> result = provider.priceEur("BTC", T);
        assertTrue(result.isPresent());
        assertEquals(8, result.get().scale());

        // Verify the value: 30000.33 / 1.07 = 28037.691588785046728971962616822...
        // HALF_UP at scale 8 → 28037.69158879
        assertEquals(0, new BigDecimal("28037.69158879").compareTo(result.get()));
    }

    @Test
    @DisplayName("Staking rewards scenario: AXS price at exact timestamp")
    void stakingRewardsScenario() {
        // AXS staking reward at a specific timestamp — only USDT pair available
        Instant stakingTs = Instant.parse("2021-10-26T02:05:31Z");
        var provider = new StubBinancePriceProvider(Map.of(
                "AXSUSDT",  new BigDecimal("130.50000000"),
                "EURUSDT",  new BigDecimal("1.16000000")
        ));

        // 130.50 / 1.16 = 112.50000000
        Optional<BigDecimal> result = provider.priceEur("AXS", stakingTs);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("112.50000000").compareTo(result.get()));
    }

    @Test
    @DisplayName("Simple Earn scenario: BUSD price at exact timestamp")
    void simpleEarnScenario() {
        // BUSD is a stablecoin; has BUSDUSDT ≈ 1.0 and EURUSDT
        Instant earnTs = Instant.parse("2021-10-30T04:03:53Z");
        var provider = new StubBinancePriceProvider(Map.of(
                "BUSDUSDT",  new BigDecimal("0.99990000"),
                "EURUSDT",   new BigDecimal("1.15500000")
        ));

        // 0.9999 / 1.155 = 0.86580...  → 0.86580087
        Optional<BigDecimal> result = provider.priceEur("BUSD", earnTs);
        assertTrue(result.isPresent());
        assertEquals(8, result.get().scale());
        assertTrue(result.get().compareTo(BigDecimal.ZERO) > 0);
    }
}


