package io.codifica.tax_tracker.service;

import io.codifica.tax_tracker.domain.entity.Account;
import io.codifica.tax_tracker.domain.entity.Asset;
import io.codifica.tax_tracker.domain.entity.Transaction;
import io.codifica.tax_tracker.domain.enum_types.TransactionType;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import io.codifica.tax_tracker.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a single {@link Transaction} row in its own {@code REQUIRES_NEW}
 * database transaction.
 *
 * <p>This class exists solely to work around the Spring AOP self-invocation
 * limitation: {@code @Transactional(REQUIRES_NEW)} only takes effect when the
 * call goes through the Spring proxy. By placing the per-row save in a
 * <em>separate</em> bean, every call from {@link TransactionPersistenceService}
 * is correctly intercepted, so a constraint violation or Hibernate error on one
 * row never poisons the session for subsequent rows.
 */
@Service
public class SingleTransactionSaver {

    private final TransactionRepository transactionRepository;

    public SingleTransactionSaver(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(ParsedTransaction p,
                     Account account,
                     Asset asset,
                     TransactionType type,
                     String sourceFile) {

        Transaction tx = Transaction.builder()
                .account(account)
                .asset(asset)
                .timestamp(p.getTimestamp())
                .type(type)
                .amount(p.getAmount())
                .unitPriceEur(p.getUnitPriceEur())
                .amountFiat(p.getFiatAmount())
                .feeAmount(p.getFeeAmount())
                .feeAsset(p.getFeeAsset())
                .feeEur(resolveFeeEur(p))
                .externalId(p.getExternalId())
                .sourceFile(sourceFile)
                .rawJson(p.getRawLine())
                .build();

        transactionRepository.save(tx);
    }

    // ...existing code...

    private java.math.BigDecimal resolveFeeEur(ParsedTransaction p) {
        if (p.getFeeAmount() == null) return null;
        if ("EUR".equals(p.getFeeAsset())) return p.getFeeAmount();
        return null;
    }
}

