package io.codifica.tax_tracker.domain.enrichment;

import io.codifica.tax_tracker.domain.importer.ParsedTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fills missing {@code unitPriceEur} values on {@link ParsedTransaction} objects
 * using a pluggable {@link PriceProvider}.
 *
 * <h2>Direct enrichment</h2>
 * For transactions with a null {@code unitPriceEur}, fetches the EUR price for the
 * asset at the transaction timestamp and returns a new enriched copy. Existing
 * non-null prices are never overwritten.
 *
 * <h2>Stablecoin-derived enrichment</h2>
 * For swap legs where one side is a known stablecoin (USDT, USDC, BUSD, DAI,
 * TUSD, FDUSD), the other leg's EUR price is derived from the trade ratio and
 * the stablecoin's EUR rate at the same timestamp.
 *
 * <pre>
 * Example: SELL 0.5 BTC / BUY 15,000 USDT (same timestamp)
 *
 *   stablecoinEurRate      = priceProvider.priceEur("USDT", t)       // e.g. 0.9250
 *   impliedQuotePerBase    = quoteAmount.divide(baseAmount, 18, HALF_UP)
 *                          = 15000 / 0.5 = 30000.000000000000000000
 *   basePriceEur           = impliedQuotePerBase
 *                              .multiply(stablecoinEurRate)
 *                              .setScale(8, HALF_UP)                   // 27750.00000000
 *   quotePriceEur          = stablecoinEurRate                         // 0.92500000
 * </pre>
 *
 * This requires a single API call for the stablecoin rather than two separate
 * calls, and correctly reflects the actual executed trade rate.
 *
 * <h2>Thread safety</h2>
 * This class is stateless; all state is in the injected {@link PriceProvider}.
 */
public class PriceEnrichmentService {

    /** Scale for all financial calculations — maximum precision. */
    private static final int INTERMEDIATE_SCALE = 18;
    /** Final scale for DB persistence — same as intermediate (no rounding loss). */
    static final int PRICE_SCALE = 18;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Symbols treated as USD-pegged stablecoins for the derivation path. */
    static final Set<String> STABLECOINS = Set.of(
            "USDT", "USDC", "BUSD", "DAI", "TUSD", "FDUSD", "USDP", "GUSD"
    );

    private final PriceProvider priceProvider;

    public PriceEnrichmentService(PriceProvider priceProvider) {
        this.priceProvider = Objects.requireNonNull(priceProvider, "priceProvider");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enriches a list of parsed transactions.
     *
     * <p>Returns a new list with enriched copies (originals are immutable).
     * The returned list preserves input order.
     *
     * @param transactions input list; may contain nulls (they are passed through unchanged)
     * @return {@link EnrichmentResult} containing the enriched list and a summary report
     */
    public EnrichmentResult enrich(List<ParsedTransaction> transactions) {
        // Phase 1: stablecoin-derived enrichment for paired swap legs
        List<ParsedTransaction> afterPhase1 = enrichStablecoinPairs(transactions);

        // Phase 2: direct enrichment for all remaining null-price transactions
        List<ParsedTransaction> afterPhase2 = new ArrayList<>(afterPhase1.size());
        int enrichedCount  = 0;
        int stillMissing   = 0;
        List<String> failures = new ArrayList<>();

        for (ParsedTransaction tx : afterPhase1) {
            if (tx.getUnitPriceEur() != null) {
                afterPhase2.add(tx);                // already has a price
                continue;
            }

            Optional<BigDecimal> price = fetchPrice(tx.getAsset(), tx.getTimestamp(), failures);
            if (price.isPresent()) {
                afterPhase2.add(tx.withUnitPriceEur(price.get()));
                enrichedCount++;
            } else {
                afterPhase2.add(tx);               // leave null, record as still-missing
                stillMissing++;
            }
        }

        // The stablecoin phase also contributed to enrichedCount; recalculate properly
        // by comparing phase-1 output vs original null states.
        int phase1Enriched = countEnriched(afterPhase1) - countEnriched(transactions);
        int totalEnriched  = phase1Enriched + enrichedCount;
        int totalMissing   = countNullPrice(afterPhase2);

        EnrichmentReport report = new EnrichmentReport(totalEnriched, totalMissing, failures);
        return new EnrichmentResult(afterPhase2, report);
    }

    // -------------------------------------------------------------------------
    // Phase 1: stablecoin pair derivation
    // -------------------------------------------------------------------------

    /**
     * Groups transactions by exact timestamp. For each group that contains exactly
     * one SELL and one BUY where one asset is a stablecoin, derives both prices
     * from a single {@link PriceProvider} call.
     */
    private List<ParsedTransaction> enrichStablecoinPairs(List<ParsedTransaction> transactions) {
        // Index by position so we can produce an ordered output with replacements
        Map<Integer, ParsedTransaction> enriched = new HashMap<>();

        // Build a map: timestamp → list of (index, tx) for null-price transactions
        Map<Instant, List<Map.Entry<Integer, ParsedTransaction>>> byTimestamp =
                new LinkedHashMap<>();
        for (int i = 0; i < transactions.size(); i++) {
            ParsedTransaction tx = transactions.get(i);
            if (tx.getUnitPriceEur() == null) {
                byTimestamp
                        .computeIfAbsent(tx.getTimestamp(), k -> new ArrayList<>())
                        .add(Map.entry(i, tx));
            }
        }

        for (List<Map.Entry<Integer, ParsedTransaction>> group : byTimestamp.values()) {
            if (group.size() < 2) continue;

            // Find exactly one SELL and one BUY within the group
            List<Map.Entry<Integer, ParsedTransaction>> sells = group.stream()
                    .filter(e -> "SELL".equals(e.getValue().getType())).toList();
            List<Map.Entry<Integer, ParsedTransaction>> buys = group.stream()
                    .filter(e -> "BUY".equals(e.getValue().getType())).toList();

            if (sells.size() != 1 || buys.size() != 1) continue;

            Map.Entry<Integer, ParsedTransaction> sellEntry = sells.get(0);
            Map.Entry<Integer, ParsedTransaction> buyEntry  = buys.get(0);
            ParsedTransaction sell = sellEntry.getValue();
            ParsedTransaction buy  = buyEntry.getValue();

            // Check if one side is a stablecoin
            boolean sellIsStable = STABLECOINS.contains(sell.getAsset());
            boolean buyIsStable  = STABLECOINS.contains(buy.getAsset());

            if (!sellIsStable && !buyIsStable) continue;

            // Identify base (non-stable) and quote (stable) sides
            ParsedTransaction baseTx  = sellIsStable ? buy  : sell;
            ParsedTransaction quoteTx = sellIsStable ? sell : buy;
            int baseIdx  = sellIsStable ? buyEntry.getKey()  : sellEntry.getKey();
            int quoteIdx = sellIsStable ? sellEntry.getKey() : buyEntry.getKey();

            List<String> tempFailures = new ArrayList<>();
            Optional<BigDecimal> stableRate = fetchPrice(
                    quoteTx.getAsset(), quoteTx.getTimestamp(), tempFailures);

            if (stableRate.isEmpty()) continue; // can't derive without stablecoin rate

            BigDecimal quotePriceEur = stableRate.get();
            BigDecimal basePriceEur  = deriveBasePrice(
                    baseTx.getAmount(), quoteTx.getAmount(), quotePriceEur);

            enriched.put(baseIdx,  baseTx.withUnitPriceEur(basePriceEur));
            enriched.put(quoteIdx, quoteTx.withUnitPriceEur(quotePriceEur));
        }

        // Reconstruct list with replacements
        List<ParsedTransaction> result = new ArrayList<>(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            result.add(enriched.getOrDefault(i, transactions.get(i)));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Derivation math
    // -------------------------------------------------------------------------

    /**
     * Derives the EUR price of the base asset from the trade ratio and stablecoin EUR rate.
     *
     * <pre>
     *   impliedQuotePerBase = quoteAmount / baseAmount          (scale 18, HALF_UP)
     *   basePriceEur        = impliedQuotePerBase × quotePriceEur  (scale 8, HALF_UP)
     * </pre>
     *
     * Division is kept at scale 18 to avoid compounding rounding error before
     * the final multiplication.
     */
    static BigDecimal deriveBasePrice(BigDecimal baseAmount,
                                      BigDecimal quoteAmount,
                                      BigDecimal quotePriceEur) {
        BigDecimal impliedQuotePerBase = quoteAmount.divide(baseAmount, INTERMEDIATE_SCALE, ROUNDING);
        return impliedQuotePerBase.multiply(quotePriceEur).setScale(PRICE_SCALE, ROUNDING);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Optional<BigDecimal> fetchPrice(String asset, Instant at, List<String> failures) {
        try {
            return priceProvider.priceEur(asset, at);
        } catch (Exception ex) {
            failures.add(asset + "@" + at + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static int countEnriched(List<ParsedTransaction> txs) {
        return (int) txs.stream().filter(t -> t.getUnitPriceEur() != null).count();
    }

    private static int countNullPrice(List<ParsedTransaction> txs) {
        return (int) txs.stream().filter(t -> t.getUnitPriceEur() == null).count();
    }

    // -------------------------------------------------------------------------
    // Result container
    // -------------------------------------------------------------------------

    /** Holds the enriched transaction list and the summary report together. */
    public record EnrichmentResult(List<ParsedTransaction> transactions, EnrichmentReport report) {}
}

