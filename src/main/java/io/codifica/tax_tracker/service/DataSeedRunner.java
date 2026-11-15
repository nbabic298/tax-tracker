package io.codifica.tax_tracker.service;

import io.codifica.tax_tracker.domain.enrichment.PriceEnrichmentService;
import io.codifica.tax_tracker.domain.importer.ImportResult;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import io.codifica.tax_tracker.domain.importer.binance.BinanceTransactionImporter;
import io.codifica.tax_tracker.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs once at application startup.
 *
 * <p>If the {@code transaction} table is empty, imports the configured Binance
 * CSV file, enriches the price of every {@code STAKING_REWARD} row via the
 * Binance klines API (deduplicated by asset+date to minimise API calls), then
 * persists all rows.
 *
 * <p>Idempotent: does nothing if the table already contains rows.
 */
@Component
public class DataSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeedRunner.class);

    /** Types whose unit price should be enriched from the Binance klines API. */
    private static final Set<String> TYPES_NEEDING_PRICE =
            Set.of("STAKING_REWARD", "AIRDROP", "BUY", "SELL");

    private final TransactionRepository transactionRepository;
    private final BinanceTransactionImporter importer;
    private final PriceEnrichmentService enrichmentService;
    private final TransactionPersistenceService persistenceService;

    @Value("${import.binance.file:src/main/resources/import-files/Binance-Transaction-History-202611131053(UTC+1)-part1-of1.csv}")
    private String binanceCsvPath;

    public DataSeedRunner(TransactionRepository transactionRepository,
                          BinanceTransactionImporter importer,
                          PriceEnrichmentService enrichmentService,
                          TransactionPersistenceService persistenceService) {
        this.transactionRepository = transactionRepository;
        this.importer              = importer;
        this.enrichmentService     = enrichmentService;
        this.persistenceService    = persistenceService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (transactionRepository.count() > 0) {
            log.info("Transaction table is not empty — skipping seed import.");
            return;
        }

        Path csvPath = Paths.get(binanceCsvPath);
        log.info("Transaction table is empty. Seeding from {}", csvPath.toAbsolutePath());

        // 1. Parse
        ImportResult importResult = importer.importTransactions(csvPath);
        log.info("Parsed {} transactions, {} warnings, {} unmatched legs",
                importResult.getTransactionCount(),
                importResult.getWarningCount(),
                importResult.getUnmatchedLegCount());

        importResult.getUnmatchedLegs().forEach(leg -> log.warn("Unmatched trade leg: {}", leg));

        // 2. Enrich prices (deduplicated by asset+date)
        List<ParsedTransaction> enriched = enrichWithDedup(importResult.getTransactions());

        // 3. Persist
        int saved = persistenceService.persistAll(
                enriched,
                "Binance",
                "exchange",
                csvPath.getFileName().toString());

        log.info("Seed import complete. Saved {} transactions.", saved);
    }

    /**
     * Enriches prices for all rows that need them, but only makes ONE API call
     * per unique (asset, UTC-date) combination. Staking rewards for the same
     * asset on the same day all share the same fetched price, reducing 326
     * potential calls to ~20-30 unique lookups.
     *
     * <p>Rows that already have a price, or whose type doesn't require a price,
     * are passed through unchanged.
     */
    private List<ParsedTransaction> enrichWithDedup(List<ParsedTransaction> all) {
        List<ParsedTransaction> toEnrich = all.stream()
                .filter(tx -> TYPES_NEEDING_PRICE.contains(tx.getType()))
                .filter(tx -> tx.getUnitPriceEur() == null)
                .toList();

        if (toEnrich.isEmpty()) return all;

        // One representative per (asset, UTC-date) — single API call per unique pair
        record AssetDate(String asset, LocalDate date) {}

        Map<AssetDate, ParsedTransaction> representatives = new LinkedHashMap<>();
        for (ParsedTransaction tx : toEnrich) {
            var key = new AssetDate(
                    tx.getAsset(),
                    tx.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
            representatives.putIfAbsent(key, tx);
        }

        log.info("Enriching prices: {} rows reduced to {} unique (asset, date) lookups",
                toEnrich.size(), representatives.size());

        PriceEnrichmentService.EnrichmentResult result =
                enrichmentService.enrich(new ArrayList<>(representatives.values()));

        log.info("Enrichment complete: enriched={}, stillMissing={}, failures={}",
                result.report().getEnrichedCount(),
                result.report().getStillMissingCount(),
                result.report().getFailures().size());
        result.report().getFailures().forEach(f -> log.warn("Enrichment failure: {}", f));

        // Build (asset, date) → price lookup from enriched representatives
        Map<AssetDate, BigDecimal> priceByAssetDate = new HashMap<>();
        int i = 0;
        for (AssetDate key : representatives.keySet()) {
            BigDecimal price = result.transactions().get(i).getUnitPriceEur();
            if (price != null) priceByAssetDate.put(key, price);
            i++;
        }

        // Fan the fetched price back out to every matching row in the full list
        return all.stream().map(tx -> {
            if (tx.getUnitPriceEur() != null) return tx;
            if (!TYPES_NEEDING_PRICE.contains(tx.getType())) return tx;
            var key = new AssetDate(
                    tx.getAsset(),
                    tx.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
            BigDecimal price = priceByAssetDate.get(key);
            return price != null ? tx.withUnitPriceEur(price) : tx;
        })
        // After enrichment, calculate amountFiat for rows with price but no fiatAmount
        .map(this::calculateAmountFiatIfNeeded)
        .collect(Collectors.toList());
    }

    /**
     * Calculates amountFiat from unitPriceEur * amount if:
     * - unitPriceEur is not null (price was enriched or pre-calculated)
     * - amountFiat is currently null
     * - type requires a price (BUY, SELL, STAKING_REWARD, AIRDROP)
     */
    private ParsedTransaction calculateAmountFiatIfNeeded(ParsedTransaction tx) {
        if (tx.getUnitPriceEur() == null || tx.getFiatAmount() != null) {
            return tx;  // Already has fiatAmount or no price
        }
        if (!TYPES_NEEDING_PRICE.contains(tx.getType())) {
            return tx;  // Type doesn't need fiatAmount
        }

        java.math.BigDecimal amountFiat = tx.getUnitPriceEur()
                .multiply(tx.getAmount())
                .setScale(18, java.math.RoundingMode.HALF_UP);

        return tx.toBuilder().fiatAmount(amountFiat).build();
    }
}
