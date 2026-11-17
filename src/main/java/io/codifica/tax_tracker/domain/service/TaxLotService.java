package io.codifica.tax_tracker.domain.service;

import io.codifica.tax_tracker.domain.entity.Account;
import io.codifica.tax_tracker.domain.entity.Asset;
import io.codifica.tax_tracker.domain.entity.TaxLot;
import io.codifica.tax_tracker.domain.entity.Transaction;
import io.codifica.tax_tracker.domain.enum_types.TransactionType;
import io.codifica.tax_tracker.repository.AssetRepository;
import io.codifica.tax_tracker.repository.TaxLotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Service for creating and managing tax lots from transactions.
 *
 * <h2>Withdrawal Fee Handling</h2>
 * When a TRANSFER_OUT transaction has a feeAmount, the fee is deducted from the
 * oldest tax lot of that asset (FIFO). The lot's originalAmount is reduced, while
 * costBasisEur remains unchanged, effectively increasing the per-unit cost.
 *
 * <h2>Crypto-to-Crypto Swaps</h2>
 * When a BUY transaction has feeAsset set (indicating a crypto-to-crypto swap):
 * - A new tax lot is created for the acquired asset
 * - The oldest lot of the spent asset is reduced by the spent amount
 * - Cost basis of the new lot comes from the spent asset's cost basis (via TaxLot.carriedOverFromLotId)
 *
 * Example:
 *   Swap: 0.5 BTC → 7.5 SOL
 *   → Create new SOL lot (7.5 units)
 *   → Reduce oldest BTC lot by 0.5 units
 */
@Service
public class TaxLotService {

    private static final Logger log = LoggerFactory.getLogger(TaxLotService.class);
    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final TaxLotRepository taxLotRepository;
    private final AssetRepository assetRepository;

    public TaxLotService(TaxLotRepository taxLotRepository, AssetRepository assetRepository) {
        this.taxLotRepository = taxLotRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Processes a transaction and creates/updates tax lots accordingly.
     *
     * <p>For BUY/AIRDROP/STAKING_REWARD with amountFiat: creates a new tax lot.
     * For BUY with feeAsset set (crypto-to-crypto swap): derives cost basis from spent asset,
     *   creates new lot, and reduces the spent asset's lot (preserves holding period).
     * For TRANSFER_OUT with fee: reduces the oldest lot by the fee amount.
     *
     * @param tx the transaction to process
     * @param account the account owning the transaction
     * @param asset the asset involved (acquired asset)
     */
    @Transactional
    public void processTransaction(Transaction tx, Account account, Asset asset) {
        if (tx.getType() == TransactionType.BUY ||
            tx.getType() == TransactionType.STAKING_REWARD ||
            tx.getType() == TransactionType.AIRDROP) {

            // Check if this is a crypto-to-crypto swap (BUY + feeAsset + no amountFiat)
            if (tx.getType() == TransactionType.BUY && tx.getFeeAsset() != null &&
                tx.getFeeAmount() != null && tx.getAmountFiat() == null) {
                // Process crypto-to-crypto swap: derive cost basis from spent asset
                processCryptoToCryptoSwap(tx, asset);
            } else if (tx.getAmountFiat() != null) {
                // Standard acquisition with cost basis
                createTaxLot(tx, asset);
            } else {
                // Acquisition without cost basis (not enriched yet)
                log.warn("Skipping tax lot creation: {} {} has no cost basis (amountFiat is null). " +
                        "Transaction may not be enriched with price yet.",
                        tx.getType(), asset.getSymbol());
            }
        } else if (tx.getType() == TransactionType.TRANSFER_OUT && tx.getFeeAmount() != null) {
            deductWithdrawalFeeFromOldestLot(tx, asset);
        }
    }

    /**
     * Processes a crypto-to-crypto swap transaction.
     *
     * <p>For a swap (e.g., 0.5 BTC → 7.5 SOL):
     * 1. Finds the oldest OPEN lot of the spent asset (BTC)
     * 2. Derives cost basis: (spent amount × cost per unit of spent asset)
     * 3. Creates new lot (SOL) with derived cost basis
     * 4. Links via carriedOverFromLotId to preserve holding period
     * 5. Reduces the spent asset's lot by the spent amount
     *
     * @param swapTx the BUY transaction with feeAsset set (crypto-to-crypto)
     * @param acquiredAsset the asset acquired (e.g., SOL)
     */
    private void processCryptoToCryptoSwap(Transaction swapTx, Asset acquiredAsset) {
        String spentAssetSymbol = swapTx.getFeeAsset();
        BigDecimal spentAmount = swapTx.getFeeAmount();

        if (spentAssetSymbol == null || spentAmount == null ||
            spentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;  // Not a valid swap
        }

        // Find the spent asset by symbol
        Asset spentAsset = assetRepository.findBySymbol(spentAssetSymbol).orElse(null);
        if (spentAsset == null) {
            log.warn("Spent asset not found for crypto-to-crypto swap: {}→{}. " +
                    "Symbol: {}. Cannot complete swap processing.",
                    spentAmount, spentAssetSymbol, acquiredAsset.getSymbol());
            return;
        }

        // Find the oldest OPEN lot of the spent asset
        List<TaxLot> spentLotsForAsset = taxLotRepository.findOpenByAssetOrderByAcquiredAtAsc(spentAsset);
        if (spentLotsForAsset.isEmpty()) {
            log.warn("No open tax lots found for spent asset in crypto-to-crypto swap: {} {} → {} {}",
                    spentAmount, spentAsset.getSymbol(), swapTx.getAmount(), acquiredAsset.getSymbol());
            return;
        }

        // Source lot (oldest, to be reduced)
        TaxLot sourceLot = spentLotsForAsset.get(0);

        // Derive cost basis: (spent amount × cost per unit of spent asset)
        BigDecimal costBasisPerUnitSpent = sourceLot.getCostBasisEur()
                .divide(sourceLot.getOriginalAmount(), SCALE, ROUNDING);

        BigDecimal swapCostBasisEur = spentAmount.multiply(costBasisPerUnitSpent)
                .setScale(SCALE, ROUNDING);

        // Create the new tax lot with derived cost basis
        TaxLot newLot = TaxLot.builder()
                .asset(acquiredAsset)
                .acquiredAt(swapTx.getTimestamp())
                .originalAmount(swapTx.getAmount())
                .remainingAmount(swapTx.getAmount())
                .costBasisEur(swapCostBasisEur)
                .sourceTransaction(swapTx)
                .carriedOverFromLot(sourceLot)  // Link to source for holding period tracking
                .build();

        taxLotRepository.save(newLot);

        log.info("Created tax lot from crypto-to-crypto swap: {} {} acquired at {}. " +
                "Cost basis: {} EUR (derived from {} @ {} EUR/unit of {}). " +
                "Swap source: {} lot (acquired {}).",
                swapTx.getAmount(), acquiredAsset.getSymbol(), swapTx.getTimestamp(),
                swapCostBasisEur, spentAmount, costBasisPerUnitSpent, spentAsset.getSymbol(),
                spentAssetSymbol, sourceLot.getAcquiredAt());

        // Now reduce the spent asset's oldest lot
        reduceLotForCryptoSwap(spentAmount, spentAsset, sourceLot, acquiredAsset, swapTx);
    }

    /**
     * Reduces the oldest lot of the spent asset in a crypto-to-crypto swap (FIFO cascade).
     *
     * <p>If the oldest lot doesn't have enough remaining amount, reduces what it can
     * from that lot (reducing to zero) and cascades the remaining spent amount to
     * the next oldest lot. Continues until all spent amount is deducted or runs out of lots.
     *
     * <p>IMPORTANT: Only remainingAmount is reduced, NOT originalAmount.
     * originalAmount is immutable and represents the original acquisition quantity.
     * When a lot's remainingAmount reaches zero, closedAt is set to mark it as closed.
     *
     * <p>Example:
     *   Spend: 0.5 BTC
     *   Lot 1: originalAmount=2.0, remaining=0.3 BTC → remaining reduced to 0, closedAt=now, remainder: 0.2 BTC
     *   Lot 2: originalAmount=1.0, remaining=1.0 BTC → remaining reduced to 0.8 BTC
     *
     * @param spentAmount the total amount of base asset spent
     * @param spentAsset the base asset (what was spent)
     * @param sourceLot the first (oldest) open lot (for reference)
     * @param acquiredAsset the acquired asset (for logging)
     * @param swapTx the swap transaction (for logging)
     */
    private void reduceLotForCryptoSwap(BigDecimal spentAmount, Asset spentAsset, TaxLot sourceLot,
                                        Asset acquiredAsset, Transaction swapTx) {
        BigDecimal remainingSpent = spentAmount;
        Instant now = Instant.now();

        // Get all OPEN lots for the spent asset (cascade across all if needed)
        List<TaxLot> spentLotsForAsset = taxLotRepository.findOpenByAssetOrderByAcquiredAtAsc(spentAsset);

        for (TaxLot lot : spentLotsForAsset) {
            if (remainingSpent.compareTo(BigDecimal.ZERO) <= 0) {
                break;  // All spent amount deducted
            }

            BigDecimal lotRemaining = lot.getRemainingAmount();
            if (lotRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;  // Skip empty lots
            }

            // Determine how much to reduce from this lot
            BigDecimal reduceFromLot = remainingSpent.min(lotRemaining);

            // Only reduce remainingAmount, NOT originalAmount (immutable)
            BigDecimal newRemaining = lot.getRemainingAmount()
                    .subtract(reduceFromLot)
                    .setScale(SCALE, ROUNDING);

            if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
                newRemaining = BigDecimal.ZERO;
            }

            lot.setRemainingAmount(newRemaining);

            // Mark lot as closed if remaining reaches zero
            if (newRemaining.compareTo(BigDecimal.ZERO) == 0) {
                lot.setClosedAt(now);
                log.info("Spent asset lot fully disposed (closed) in crypto-to-crypto swap: " +
                        "{} {} → {} {}. Spent lot ({} from {}, original={}) reduced by {}.",
                        spentAmount, spentAsset.getSymbol(), swapTx.getAmount(), acquiredAsset.getSymbol(),
                        spentAsset.getSymbol(), lot.getAcquiredAt(), lot.getOriginalAmount(), reduceFromLot);
            } else {
                log.info("Reduced spent asset lot in crypto-to-crypto swap: {} {} → {} {}. " +
                        "Spent {} from {} lot (acquired {}, original={}). Lot now: remaining={}",
                        spentAmount, spentAsset.getSymbol(), swapTx.getAmount(), acquiredAsset.getSymbol(),
                        reduceFromLot, spentAsset.getSymbol(), lot.getAcquiredAt(), lot.getOriginalAmount(), newRemaining);
            }

            taxLotRepository.save(lot);

            // Update remaining spent
            remainingSpent = remainingSpent.subtract(reduceFromLot)
                    .setScale(SCALE, ROUNDING);
        }

        if (remainingSpent.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Could not fully reduce spent asset lot in crypto-to-crypto swap. " +
                    "Remaining {} {} unaccounted for. Swap: {} {} → {} {}",
                    remainingSpent, spentAsset.getSymbol(), spentAmount, spentAsset.getSymbol(),
                    swapTx.getAmount(), acquiredAsset.getSymbol());
        }
    }

    /**
     * Creates a new tax lot from a BUY, STAKING_REWARD, or AIRDROP transaction.
     */
    private void createTaxLot(Transaction tx, Asset asset) {
        TaxLot lot = TaxLot.builder()
                .asset(asset)
                .acquiredAt(tx.getTimestamp())
                .originalAmount(tx.getAmount())
                .remainingAmount(tx.getAmount())
                .costBasisEur(tx.getAmountFiat())
                .sourceTransaction(tx)
                .build();

        taxLotRepository.save(lot);
        log.debug("Created tax lot: {} {} acquired at {} with cost basis {}",
                tx.getAmount(), asset.getSymbol(), tx.getTimestamp(), tx.getAmountFiat());
    }

    /**
     * Reduces tax lots by the withdrawal fee amount (FIFO cascade).
     *
     * <p>If the oldest lot doesn't have enough remaining amount, deducts what it can
     * from that lot (reducing to zero) and cascades the remaining fee to the next
     * oldest lot. Continues until all fee is deducted or we run out of lots.
     *
     * <p>Cost basis is never changed—only originalAmount and remainingAmount decrease.
     * When a lot's remainingAmount reaches zero, closedAt is set to mark it as closed.
     *
     * <p>Example:
     *   Fee: 0.015 SOL
     *   Lot 1: 0.005 SOL → reduced to 0 SOL, closedAt=now, fee remainder: 0.010 SOL
     *   Lot 2: 0.050 SOL → reduced to 0.040 SOL, fee satisfied
     *
     * @param withdrawalTx the TRANSFER_OUT transaction with a fee
     * @param asset the asset being withdrawn
     */
    private void deductWithdrawalFeeFromOldestLot(Transaction withdrawalTx, Asset asset) {
        BigDecimal remainingFee = withdrawalTx.getFeeAmount();
        if (remainingFee == null || remainingFee.compareTo(BigDecimal.ZERO) <= 0) {
            return;  // No fee to deduct
        }

        // Find all OPEN lots for this asset, ordered oldest first
        // (closedAt IS NULL filters out fully disposed lots)
        List<TaxLot> lotsForAsset = taxLotRepository.findOpenByAssetOrderByAcquiredAtAsc(asset);

        if (lotsForAsset.isEmpty()) {
            log.warn("No open tax lots found for withdrawal fee deduction: {} {} at {}. " +
                    "Transaction history may be incomplete or out of order.",
                    remainingFee, asset.getSymbol(), withdrawalTx.getTimestamp());
            return;
        }

        Instant now = Instant.now();

        // Cascade fee deduction across lots until satisfied
        for (TaxLot lot : lotsForAsset) {
            if (remainingFee.compareTo(BigDecimal.ZERO) <= 0) {
                break;  // Fee fully deducted
            }

            BigDecimal lotRemaining = lot.getRemainingAmount();
            if (lotRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;  // Skip empty lots (shouldn't happen with open filter, but be safe)
            }

            // Determine how much to deduct from this lot
            BigDecimal deductFromLot = remainingFee.min(lotRemaining);

            // Reduce both original and remaining amounts
            BigDecimal newOriginal = lot.getOriginalAmount()
                    .subtract(deductFromLot)
                    .setScale(SCALE, ROUNDING);
            BigDecimal newRemaining = lot.getRemainingAmount()
                    .subtract(deductFromLot)
                    .setScale(SCALE, ROUNDING);

            // Clamp to zero (should not be needed, but be safe)
            if (newOriginal.compareTo(BigDecimal.ZERO) < 0) {
                newOriginal = BigDecimal.ZERO;
            }
            if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
                newRemaining = BigDecimal.ZERO;
            }

            lot.setOriginalAmount(newOriginal);
            lot.setRemainingAmount(newRemaining);

            // Mark lot as closed if remainingAmount is now zero
            if (newRemaining.compareTo(BigDecimal.ZERO) == 0) {
                lot.setClosedAt(now);
                log.info("Tax lot fully disposed (closed): {} {} acquired {}. " +
                        "Deducted {} via withdrawal fee.",
                        lot.getOriginalAmount(), asset.getSymbol(), lot.getAcquiredAt(),
                        deductFromLot);
            } else {
                log.info("Deducted {} {} from open lot (acquired {}). Lot now: original={}, remaining={}, costBasis={}",
                        deductFromLot, asset.getSymbol(), lot.getAcquiredAt(),
                        newOriginal, newRemaining, lot.getCostBasisEur());
            }

            taxLotRepository.save(lot);

            // Reduce remaining fee by what we just deducted
            remainingFee = remainingFee.subtract(deductFromLot)
                    .setScale(SCALE, ROUNDING);
        }

        // If we still have undeducted fee, log a warning
        if (remainingFee.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Could not fully deduct withdrawal fee. Remaining {} {} unaccounted for. " +
                    "Total open lots for asset: {}. Total available: {}",
                    remainingFee, asset.getSymbol(), lotsForAsset.size(),
                    lotsForAsset.stream()
                            .map(TaxLot::getRemainingAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }
}




