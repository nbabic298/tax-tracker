package io.codifica.tax_tracker.service;

import io.codifica.tax_tracker.domain.enrichment.PriceEnrichmentService;
import io.codifica.tax_tracker.domain.importer.ImportResult;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import io.codifica.tax_tracker.domain.importer.binance.BinanceTransactionImporter;
import io.codifica.tax_tracker.domain.importer.binance.BinanceWithdrawImporter;
import io.codifica.tax_tracker.domain.service.TaxLotService;
import io.codifica.tax_tracker.repository.AccountRepository;
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
    private final BinanceTransactionImporter transactionImporter;
    private final BinanceWithdrawImporter withdrawImporter;
    private final PriceEnrichmentService enrichmentService;
    private final TransactionPersistenceService persistenceService;
    private final TaxLotService taxLotService;
    private final AccountRepository accountRepository;

    @Value("${import.binance.file:src/main/resources/import-files/Binance-Transaction-History-202611131053(UTC+1)-part1-of1.csv}")
    private String binanceCsvPath;

    @Value("${import.binance.withdraw-file:src/main/resources/import-files/Binance-Withdraw-History-202611141647(UTC+1)-part1-of1.csv}")
    private String binanceWithdrawCsvPath;

    public DataSeedRunner(TransactionRepository transactionRepository,
                          BinanceTransactionImporter transactionImporter,
                          BinanceWithdrawImporter withdrawImporter,
                          PriceEnrichmentService enrichmentService,
                          TransactionPersistenceService persistenceService,
                          TaxLotService taxLotService,
                          AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.transactionImporter = transactionImporter;
        this.withdrawImporter = withdrawImporter;
        this.enrichmentService = enrichmentService;
        this.persistenceService = persistenceService;
        this.taxLotService = taxLotService;
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (transactionRepository.count() > 0) {
            log.info("Transaction table is not empty — skipping seed import.");
            return;
        }

        Path txPath = Paths.get(binanceCsvPath);
        Path withdrawPath = Paths.get(binanceWithdrawCsvPath);

        log.info("Transaction table is empty. Seeding from {} and {}",
                txPath.toAbsolutePath(), withdrawPath.toAbsolutePath());

        // Phase 1: Import transaction history
        ImportResult txResult = transactionImporter.importTransactions(txPath);
        log.info("Transaction history: {} parsed, {} warnings, {} unmatched legs",
                txResult.getTransactionCount(),
                txResult.getWarningCount(),
                txResult.getUnmatchedLegCount());

        txResult.getUnmatchedLegs().forEach(leg -> log.warn("Unmatched trade leg: {}", leg));

        // Phase 2: Import withdraw history
        ImportResult withdrawResult = withdrawImporter.importTransactions(withdrawPath);
        log.info("Withdraw history: {} completed withdrawals parsed",
                withdrawResult.getTransactionCount());

        // Phase 3: Combine all transactions
        List<ParsedTransaction> allTransactions = new ArrayList<>(txResult.getTransactions());
        allTransactions.addAll(withdrawResult.getTransactions());
        log.info("Total transactions before enrichment: {}", allTransactions.size());

        // Phase 4: Enrich prices (selective)
        List<ParsedTransaction> enriched = enrichWithDedup(allTransactions);

        // Phase 5: Persist all
        int saved = persistenceService.persistAll(
                enriched,
                "Binance",
                "exchange",
                "seed-import");

        log.info("Seed import complete. Saved {} transactions.", saved);

        // Phase 6: Process all persisted transactions through TaxLotService
        // This creates tax lots, handles withdrawals, swaps, etc.
        processTaxLots();

        log.info("Tax lot processing complete.");
    }

    /**
     * Processes all persisted transactions through TaxLotService to create and manage tax lots.
     * This creates tax lots for acquisitions, reduces lots for withdrawals, and handles swaps.
     *
     * Must be @Transactional to keep the session open while accessing lazy-loaded associations
     * (Asset, Account) within TaxLotService.
     */
    @org.springframework.transaction.annotation.Transactional
    private void processTaxLots() {
        long totalTransactions = transactionRepository.count();
        log.info("Processing {} transactions through TaxLotService...", totalTransactions);

        // Fetch Binance account (we seeded as "Binance" / "exchange")
        var binanceAccount = accountRepository.findByNameAndType("Binance", "exchange")
                .orElseThrow(() -> new IllegalStateException("Binance account not found after seed import"));

        // Process each transaction in order (important for FIFO)
        // Use eager fetch query to load Asset and Account associations upfront
        int processed = 0;
        for (var tx : transactionRepository.findAllWithAssetAndAccountEager()) {
            try {
                // Access tx.getAsset() while still within the transaction session
                taxLotService.processTransaction(tx, binanceAccount, tx.getAsset());
                processed++;

                if (processed % 100 == 0) {
                    log.debug("Processed {} transactions...", processed);
                }
            } catch (Exception ex) {
                log.error("Failed to process transaction {} {}: {}",
                        tx.getType(), tx.getAsset().getSymbol(), ex.getMessage(), ex);
            }
        }

        log.info("Tax lot processing complete. Processed {} transactions.", processed);
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
     *
     * Skips crypto-to-crypto trades (feeAsset != null) as their cost basis
     * is derived from the source transaction during TaxLot creation.
     */
    private ParsedTransaction calculateAmountFiatIfNeeded(ParsedTransaction tx) {
        if (tx.getFiatAmount() != null) {
            return tx;  // Already has fiatAmount
        }
        if (tx.getUnitPriceEur() == null) {
            return tx;  // No price yet
        }
        if (!TYPES_NEEDING_PRICE.contains(tx.getType())) {
            return tx;  // Type doesn't need fiatAmount
        }
        if (tx.getFeeAsset() != null && tx.getFeeAmount() != null) {
            return tx;  // Crypto-to-crypto swap; cost basis derived from source transaction
        }

        // Standard case: unitPriceEur × amount
        BigDecimal amountFiat = tx.getUnitPriceEur()
                .multiply(tx.getAmount())
                .setScale(18, java.math.RoundingMode.HALF_UP);

        return tx.toBuilder().fiatAmount(amountFiat).build();
    }
}
