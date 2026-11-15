package io.codifica.tax_tracker.domain.enrichment;

import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.codifica.tax_tracker.domain.enrichment.PriceEnrichmentService.PRICE_SCALE;
import static io.codifica.tax_tracker.domain.enrichment.PriceEnrichmentService.ROUNDING;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PriceEnrichmentService")
class PriceEnrichmentServiceTest {

    // Fixed timestamps (different days → different cache keys)
    private static final Instant T1 = Instant.parse("2021-10-24T14:17:05Z");
    private static final Instant T2 = Instant.parse("2021-11-15T09:00:00Z");
    private static final Instant T3 = Instant.parse("2022-01-05T12:00:00Z");

    private static final String SOURCE = "TestImporter";

    // -------------------------------------------------------------------------
    // Mock PriceProvider builder
    // -------------------------------------------------------------------------

    /** Builds a mock PriceProvider that returns fixed prices from a map. */
    private PriceProvider mockProvider(Map<String, BigDecimal> prices) {
        return (symbol, at) -> Optional.ofNullable(prices.get(symbol));
    }

    /** Counts how many transactions still have null unitPriceEur. */
    private long nullPriceCount(List<ParsedTransaction> txs) {
        return txs.stream().filter(t -> t.getUnitPriceEur() == null).count();
    }

    private ParsedTransaction tx(String asset, String type, BigDecimal amount,
                                  BigDecimal price, Instant ts) {
        return ParsedTransaction.builder(SOURCE, ts, asset, amount, type)
                .unitPriceEur(price)
                .build();
    }

    private ParsedTransaction txNoPrice(String asset, String type, BigDecimal amount, Instant ts) {
        return tx(asset, type, amount, null, ts);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Direct enrichment")
    class DirectEnrichment {

        @Test
        @DisplayName("Fills null unitPriceEur from PriceProvider")
        void fillsMissingPrice() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("BTC", new BigDecimal("27000.12345678"))));

            var txs = List.of(txNoPrice("BTC", "BUY", new BigDecimal("0.5"), T1));
            var result = service.enrich(txs);

            assertEquals(1, result.transactions().size());
            assertEquals(0, new BigDecimal("27000.12345678")
                    .compareTo(result.transactions().get(0).getUnitPriceEur()));
            assertEquals(1, result.report().getEnrichedCount());
            assertEquals(0, result.report().getStillMissingCount());
        }

        @Test
        @DisplayName("Never overwrites an existing non-null price")
        void doesNotOverwriteExistingPrice() {
            var originalPrice = new BigDecimal("30000.00000000");
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("BTC", new BigDecimal("99999.00000000"))));

            var txs = List.of(tx("BTC", "BUY", new BigDecimal("1"), originalPrice, T1));
            var result = service.enrich(txs);

            assertEquals(0, originalPrice.compareTo(result.transactions().get(0).getUnitPriceEur()));
            assertEquals(0, result.report().getEnrichedCount());
        }

        @Test
        @DisplayName("Records still-missing count when provider returns empty")
        void recordsStillMissingWhenProviderEmpty() {
            var service = new PriceEnrichmentService(mockProvider(Map.of()));

            var txs = List.of(
                    txNoPrice("BTC", "BUY", new BigDecimal("1"), T1),
                    txNoPrice("ETH", "BUY", new BigDecimal("2"), T2));
            var result = service.enrich(txs);

            assertEquals(0, result.report().getEnrichedCount());
            assertEquals(2, result.report().getStillMissingCount());
            assertEquals(2, nullPriceCount(result.transactions()));
        }

        @Test
        @DisplayName("Records failure message when provider throws")
        void recordsFailureOnException() {
            PriceProvider failing = (symbol, at) -> {
                throw new RuntimeException("HTTP 429 Too Many Requests");
            };
            var service = new PriceEnrichmentService(failing);

            var txs = List.of(txNoPrice("BTC", "BUY", new BigDecimal("1"), T1));
            var result = service.enrich(txs);

            assertEquals(1, result.report().getFailures().size());
            assertTrue(result.report().getFailures().get(0).contains("HTTP 429"));
            assertEquals(1, result.report().getStillMissingCount());
        }

        @Test
        @DisplayName("Enriches mixed list of null and non-null prices correctly")
        void enrichesMixedList() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of(
                            "BTC", new BigDecimal("27000.00000000"),
                            "ETH", new BigDecimal("1800.00000000"))));

            var btcWithPrice = tx("BTC", "BUY", new BigDecimal("1"), new BigDecimal("26000"), T1);
            var ethNullPrice = txNoPrice("ETH", "BUY", new BigDecimal("2"), T2);
            var adaNullPrice = txNoPrice("ADA", "BUY", new BigDecimal("100"), T3); // no price in provider

            var result = service.enrich(List.of(btcWithPrice, ethNullPrice, adaNullPrice));

            // BTC: unchanged
            assertEquals(0, new BigDecimal("26000").compareTo(
                    result.transactions().get(0).getUnitPriceEur()));
            // ETH: enriched
            assertEquals(0, new BigDecimal("1800.00000000").compareTo(
                    result.transactions().get(1).getUnitPriceEur()));
            // ADA: still missing
            assertNull(result.transactions().get(2).getUnitPriceEur());

            assertEquals(1, result.report().getEnrichedCount());
            assertEquals(1, result.report().getStillMissingCount());
        }

        @Test
        @DisplayName("Empty input produces empty output with zero counts")
        void emptyInputProducesEmptyOutput() {
            var service = new PriceEnrichmentService(mockProvider(Map.of()));
            var result = service.enrich(List.of());

            assertTrue(result.transactions().isEmpty());
            assertEquals(0, result.report().getEnrichedCount());
            assertEquals(0, result.report().getStillMissingCount());
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Stablecoin-derived enrichment")
    class StablecoinDerivedEnrichment {

        /**
         * Scenario: Binance BTC/USDT trade at same timestamp.
         *   SELL 0.5 BTC, BUY 15,000 USDT
         *   USDT/EUR = 0.9250
         *
         * Expected:
         *   impliedUsdtPerBtc = 15000 / 0.5             = 30000 (scale 18)
         *   BTC/EUR           = 30000 × 0.9250           = 27750.00000000
         *   USDT/EUR          = 0.92500000
         */
        @Test
        @DisplayName("Derives BTC price from USDT leg: (quoteAmount/baseAmount) × stablecoinEurRate")
        void derivesBtcPriceFromUsdt() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("USDT", new BigDecimal("0.9250"))));

            var sell = txNoPrice("BTC",  "SELL", new BigDecimal("0.5"),     T1);
            var buy  = txNoPrice("USDT", "BUY",  new BigDecimal("15000"),   T1);

            var result = service.enrich(List.of(sell, buy));
            var txs = result.transactions();

            BigDecimal expectedBtcPrice  = new BigDecimal("27750.00000000");
            BigDecimal expectedUsdtPrice = new BigDecimal("0.92500000");

            assertEquals(0, expectedBtcPrice.compareTo(txs.get(0).getUnitPriceEur()),
                    "BTC price should be derived as 27750");
            assertEquals(0, expectedUsdtPrice.compareTo(txs.get(1).getUnitPriceEur()),
                    "USDT price should be the fetched rate");
            assertEquals(2, result.report().getEnrichedCount());
            assertEquals(0, result.report().getStillMissingCount());
        }

        /**
         * Scenario: inverted side — SELL USDT / BUY ETH at same timestamp.
         *   SELL 3,000 USDT, BUY 1.6 ETH
         *   USDT/EUR = 0.9250
         *
         * Expected:
         *   impliedUsdtPerEth = 3000 / 1.6             = 1875.000000000000000000 (scale 18)
         *   ETH/EUR           = 1875 × 0.9250           = 1734.37500000
         */
        @Test
        @DisplayName("Derives ETH price when stablecoin is on SELL side")
        void derivesEthPriceWhenStablecoinOnSellSide() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("USDT", new BigDecimal("0.9250"))));

            var sell = txNoPrice("USDT", "SELL", new BigDecimal("3000"), T1);
            var buy  = txNoPrice("ETH",  "BUY",  new BigDecimal("1.6"),  T1);

            var result = service.enrich(List.of(sell, buy));
            var txs = result.transactions();

            BigDecimal expectedEthPrice  = new BigDecimal("1734.37500000");
            BigDecimal expectedUsdtPrice = new BigDecimal("0.92500000");

            assertEquals(0, expectedEthPrice.compareTo(txs.get(1).getUnitPriceEur()),
                    "ETH price should be derived as 1734.37500000");
            assertEquals(0, expectedUsdtPrice.compareTo(txs.get(0).getUnitPriceEur()),
                    "USDT price should be the fetched rate");
        }

        @Test
        @DisplayName("Works with USDC as stablecoin")
        void worksWithUsdc() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("USDC", new BigDecimal("0.9240"))));

            var sell = txNoPrice("ADA",  "SELL", new BigDecimal("1000"), T1);
            var buy  = txNoPrice("USDC", "BUY",  new BigDecimal("450"),  T1);

            var result = service.enrich(List.of(sell, buy));

            // 450 / 1000 = 0.45; 0.45 × 0.9240 = 0.41580000
            BigDecimal expected = new BigDecimal("0.41580000");
            assertEquals(0, expected.compareTo(result.transactions().get(0).getUnitPriceEur()));
        }

        @Test
        @DisplayName("Falls back to direct enrichment when stablecoin price unavailable")
        void fallsBackToDirectEnrichmentWhenStablecoinUnavailable() {
            // USDT not in provider, BTC is
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("BTC", new BigDecimal("27000.00000000"))));

            var sell = txNoPrice("BTC",  "SELL", new BigDecimal("0.5"),   T1);
            var buy  = txNoPrice("USDT", "BUY",  new BigDecimal("15000"), T1);

            var result = service.enrich(List.of(sell, buy));

            // BTC should be enriched directly; USDT still missing (not in provider)
            assertEquals(0, new BigDecimal("27000.00000000")
                    .compareTo(result.transactions().get(0).getUnitPriceEur()));
            assertNull(result.transactions().get(1).getUnitPriceEur());
            assertEquals(1, result.report().getStillMissingCount());
        }

        @Test
        @DisplayName("Does not derive when no stablecoin in pair (BTC/ETH swap)")
        void doesNotDeriveForNonStablecoinPair() {
            // Both BTC and ETH prices are available; no stablecoin involved
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of(
                            "BTC", new BigDecimal("27000.00000000"),
                            "ETH", new BigDecimal("1800.00000000"))));

            var sell = txNoPrice("BTC", "SELL", new BigDecimal("0.5"), T1);
            var buy  = txNoPrice("ETH", "BUY",  new BigDecimal("7.5"), T1);

            var result = service.enrich(List.of(sell, buy));

            // Both should be enriched via direct path
            assertEquals(0, new BigDecimal("27000.00000000")
                    .compareTo(result.transactions().get(0).getUnitPriceEur()));
            assertEquals(0, new BigDecimal("1800.00000000")
                    .compareTo(result.transactions().get(1).getUnitPriceEur()));
        }

        @Test
        @DisplayName("Transactions at different timestamps are not paired for derivation")
        void doesNotPairTransactionsAtDifferentTimestamps() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of("USDT", new BigDecimal("0.9250"))));

            var sell = txNoPrice("BTC",  "SELL", new BigDecimal("0.5"),   T1);
            var buy  = txNoPrice("USDT", "BUY",  new BigDecimal("15000"), T2); // different timestamp

            var result = service.enrich(List.of(sell, buy));

            // No derivation because different timestamps; USDT enriched directly, BTC missing
            assertNull(result.transactions().get(0).getUnitPriceEur(),
                    "BTC should not be derived — timestamps differ");
            assertEquals(0, new BigDecimal("0.92500000").compareTo(
                    result.transactions().get(1).getUnitPriceEur()));
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("deriveBasePrice() math")
    class DeriveBasePriceMath {

        @Test
        @DisplayName("Scale 8, HALF_UP — reference case")
        void referenceCase() {
            BigDecimal base  = new BigDecimal("0.5");
            BigDecimal quote = new BigDecimal("15000");
            BigDecimal rate  = new BigDecimal("0.9250");

            BigDecimal result = PriceEnrichmentService.deriveBasePrice(base, quote, rate);

            assertEquals(PRICE_SCALE, result.scale());
            assertEquals(0, new BigDecimal("27750.00000000").compareTo(result));
        }

        @Test
        @DisplayName("Rounding: result is HALF_UP at scale 8")
        void roundingHalfUp() {
            // 100 / 3 = 33.333... × 1.0000 → 33.33333333 (HALF_UP)
            BigDecimal base  = new BigDecimal("3");
            BigDecimal quote = new BigDecimal("100");
            BigDecimal rate  = new BigDecimal("1.00000000");

            BigDecimal result = PriceEnrichmentService.deriveBasePrice(base, quote, rate);

            assertEquals(PRICE_SCALE, result.scale());
            // 100/3 = 33.33333333... → HALF_UP scale 8 = 33.33333333
            assertEquals(0, new BigDecimal("33.33333333").compareTo(result));
        }

        @Test
        @DisplayName("High-precision intermediate avoids early rounding loss")
        void highPrecisionIntermediate() {
            // If intermediate was rounded to scale 8 we'd get a different final result
            BigDecimal base  = new BigDecimal("0.00000001"); // 1 satoshi
            BigDecimal quote = new BigDecimal("0.00031");
            BigDecimal rate  = new BigDecimal("0.9250");

            BigDecimal result = PriceEnrichmentService.deriveBasePrice(base, quote, rate);

            // impliedQuotePerBase = 0.00031 / 0.00000001 = 31000
            // × 0.9250 = 28675.00000000
            assertEquals(0, new BigDecimal("28675.00000000").compareTo(result));
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Order preservation")
    class OrderPreservation {

        @Test
        @DisplayName("Output list order matches input list order")
        void preservesOrder() {
            var service = new PriceEnrichmentService(
                    mockProvider(Map.of(
                            "BTC", new BigDecimal("27000"),
                            "ETH", new BigDecimal("1800"),
                            "ADA", new BigDecimal("0.50"))));

            var txs = List.of(
                    txNoPrice("ADA", "BUY", new BigDecimal("100"), T1),
                    txNoPrice("BTC", "BUY", new BigDecimal("0.1"), T2),
                    txNoPrice("ETH", "BUY", new BigDecimal("2"),   T3));

            var result = service.enrich(txs);

            assertEquals("ADA", result.transactions().get(0).getAsset());
            assertEquals("BTC", result.transactions().get(1).getAsset());
            assertEquals("ETH", result.transactions().get(2).getAsset());
        }
    }
}

