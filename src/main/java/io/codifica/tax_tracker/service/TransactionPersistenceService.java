package io.codifica.tax_tracker.service;

import io.codifica.tax_tracker.domain.entity.Account;
import io.codifica.tax_tracker.domain.entity.Asset;
import io.codifica.tax_tracker.domain.entity.Transaction;
import io.codifica.tax_tracker.domain.enum_types.AssetType;
import io.codifica.tax_tracker.domain.enum_types.TransactionType;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import io.codifica.tax_tracker.repository.AccountRepository;
import io.codifica.tax_tracker.repository.AssetRepository;
import io.codifica.tax_tracker.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps {@link ParsedTransaction} objects to JPA entities and persists them.
 *
 * <h2>All-or-nothing semantics</h2>
 * Validates all rows before persisting any. If validation fails for any row,
 * the entire batch is rejected. Otherwise, all rows are persisted in a single
 * database transaction.
 */
@Service
public class TransactionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TransactionPersistenceService.class);

    private final TransactionRepository transactionRepository;
    private final AssetRepository assetRepository;
    private final AccountRepository accountRepository;

    public TransactionPersistenceService(TransactionRepository transactionRepository,
                                         AssetRepository assetRepository,
                                         AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.assetRepository = assetRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Persists a list of parsed transactions with all-or-nothing semantics.
     *
     * <p>Validation phase: checks that all rows can be mapped to valid transaction types.
     * If any row fails validation, the entire batch is rejected with an exception.
     *
     * <p>Persistence phase: creates/fetches all assets and accounts, then persists all rows
     * in a single database transaction.
     *
     * @param parsed      list of enriched (or partially enriched) parsed transactions
     * @param accountName exchange name, e.g. "Binance"
     * @param accountType account type, e.g. "exchange"
     * @param sourceFile  original filename for audit trail
     * @return number of rows persisted
     * @throws IllegalArgumentException if any row has an invalid transaction type
     */
    @Transactional
    public int persistAll(List<ParsedTransaction> parsed,
                          String accountName,
                          String accountType,
                          String sourceFile) {

        // Phase 1: Validate all rows upfront — fail fast if any row is invalid
        List<ValidatedRow> validatedRows = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedTransaction p = parsed.get(i);
            TransactionType type = mapType(p.getType());
            if (type == null) {
                throw new IllegalArgumentException(
                        "Row " + (i + 1) + ": unknown transaction type '" + p.getType()
                        + "' for asset " + p.getAsset());
            }
            validatedRows.add(new ValidatedRow(p, type));
        }

        log.info("Validation passed for {} rows", validatedRows.size());

        // Phase 2: Find or create account (once, reused for all rows)
        Account account = findOrCreateAccount(accountName, accountType);

        // Phase 3: Find or create assets (build cache)
        Map<String, Asset> assetCache = new ConcurrentHashMap<>();
        for (ValidatedRow vr : validatedRows) {
            assetCache.computeIfAbsent(vr.transaction.getAsset(), this::findOrCreateAsset);
        }

        // Phase 4: Persist all rows in this same transaction
        for (ValidatedRow vr : validatedRows) {
            Asset asset = assetCache.get(vr.transaction.getAsset());
            Transaction tx = Transaction.builder()
                    .account(account)
                    .asset(asset)
                    .timestamp(vr.transaction.getTimestamp())
                    .type(vr.type)
                    .amount(vr.transaction.getAmount())
                    .unitPriceEur(vr.transaction.getUnitPriceEur())
                    .amountFiat(vr.transaction.getFiatAmount())
                    .feeAmount(vr.transaction.getFeeAmount())
                    .feeAsset(vr.transaction.getFeeAsset())
                    .feeEur(resolveFeeEur(vr.transaction))
                    .externalId(vr.transaction.getExternalId())
                    .sourceFile(sourceFile)
                    .rawJson(vr.transaction.getRawLine())
                    .build();

            transactionRepository.save(tx);
        }

        log.info("Persisted all {} transactions from {}", validatedRows.size(), sourceFile);
        return validatedRows.size();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Account findOrCreateAccount(String name, String type) {
        return accountRepository.findByNameAndType(name, type)
                .orElseGet(() -> {
                    log.info("Creating account: {} / {}", name, type);
                    return accountRepository.save(Account.builder().name(name).type(type).build());
                });
    }

    private Asset findOrCreateAsset(String symbol) {
        return assetRepository.findBySymbol(symbol)
                .orElseGet(() -> {
                    AssetType assetType = guessAssetType(symbol);
                    log.info("Creating asset: {} ({})", symbol, assetType);
                    return assetRepository.save(
                            Asset.builder()
                                    .symbol(symbol)
                                    .name(symbol)
                                    .assetType(assetType)
                                    .build());
                });
    }

    private AssetType guessAssetType(String symbol) {
        return switch (symbol) {
            case "Gold", "Silver", "Palladium", "Platinum" -> AssetType.METAL;
            case "EUR", "USD", "GBP", "BCPEUR", "CHF" -> AssetType.FIAT;
            default -> AssetType.CRYPTO;
        };
    }

    private java.math.BigDecimal resolveFeeEur(ParsedTransaction p) {
        // If there's a fee in EUR, use it
        if (p.getFeeAmount() != null && "EUR".equals(p.getFeeAsset())) {
            return p.getFeeAmount();
        }
        // Otherwise, use spread (Bitpanda trading cost)
        if (p.getSpread() != null) {
            return p.getSpread();
        }
        return null;
    }

    private TransactionType mapType(String type) {
        try {
            return TransactionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Holds a parsed transaction and its validated type.
     */
    private static class ValidatedRow {
        final ParsedTransaction transaction;
        final TransactionType type;

        ValidatedRow(ParsedTransaction transaction, TransactionType type) {
            this.transaction = transaction;
            this.type = type;
        }
    }
}

