package io.codifica.tax_tracker.service;

import io.codifica.tax_tracker.domain.entity.Account;
import io.codifica.tax_tracker.domain.entity.Asset;
import io.codifica.tax_tracker.domain.enum_types.AssetType;
import io.codifica.tax_tracker.domain.enum_types.TransactionType;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import io.codifica.tax_tracker.repository.AccountRepository;
import io.codifica.tax_tracker.repository.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps {@link ParsedTransaction} objects to JPA entities and persists them.
 *
 * <h2>Transaction isolation strategy</h2>
 * Each row is delegated to {@link SingleTransactionSaver#save}, which runs in
 * its own {@code REQUIRES_NEW} database transaction (separate Spring bean →
 * proxy intercepts the annotation). A failure on row N rolls back only that row;
 * rows N+1…M are unaffected and the Hibernate session in this bean stays clean.
 */
@Service
public class TransactionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TransactionPersistenceService.class);

    private final AssetRepository assetRepository;
    private final AccountRepository accountRepository;
    private final SingleTransactionSaver singleSaver;

    public TransactionPersistenceService(AssetRepository assetRepository,
                                         AccountRepository accountRepository,
                                         SingleTransactionSaver singleSaver) {
        this.assetRepository  = assetRepository;
        this.accountRepository = accountRepository;
        this.singleSaver       = singleSaver;
    }

    /**
     * Persists a list of parsed transactions, each in its own DB transaction.
     *
     * @param parsed      list of enriched (or partially enriched) parsed transactions
     * @param accountName exchange name, e.g. "Binance"
     * @param accountType account type, e.g. "exchange"
     * @param sourceFile  original filename for audit trail
     * @return number of rows actually saved
     */
    @Transactional
    public int persistAll(List<ParsedTransaction> parsed,
                          String accountName,
                          String accountType,
                          String sourceFile) {

        Account account = findOrCreateAccount(accountName, accountType);
        Map<String, Asset> assetCache = new ConcurrentHashMap<>();

        int saved = 0;
        int skipped = 0;
        for (ParsedTransaction p : parsed) {
            TransactionType type = mapType(p.getType());
            if (type == null) {
                log.warn("Skipping unknown type '{}' for asset {}", p.getType(), p.getAsset());
                skipped++;
                continue;
            }
            Asset asset = assetCache.computeIfAbsent(p.getAsset(), this::findOrCreateAsset);
            try {
                // Delegates to a separate bean so REQUIRES_NEW is honoured by the proxy
                singleSaver.save(p, account, asset, type, sourceFile);
                saved++;
            } catch (Exception ex) {
                log.error("Failed to persist {}/{} at {}: {}",
                        p.getType(), p.getAsset(), p.getTimestamp(), ex.getMessage());
            }
        }
        log.info("Persisted {}/{} transactions from {} ({} skipped)",
                saved, parsed.size(), sourceFile, skipped);
        return saved;
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
            default -> AssetType.CRYPTO;
        };
    }

    private TransactionType mapType(String type) {
        try {
            return TransactionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

