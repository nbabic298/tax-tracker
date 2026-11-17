package io.codifica.tax_tracker.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TaxLot domain entity representing a cohort of asset units with the same acquisition date,
 * used for FIFO disposal tracking and capital gains calculation.
 *
 * Per CLAUDE.md hard rules:
 * - FIFO is mandatory and UNIVERSAL across all exchanges/wallets for the same asset.
 * - Holding period > 2 years from original acquisition => gain is tax-exempt.
 * - Holding period carries over through crypto-to-crypto swaps via carriedOverFromLotId.
 */
@Entity
@Table(name = "tax_lot", indexes = {
        @Index(name = "idx_tax_lot_asset_acquired", columnList = "asset_id,acquired_at"),
        @Index(name = "idx_tax_lot_asset", columnList = "asset_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key to Asset.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /**
     * Business acquisition date (when units were originally acquired).
     * For swaps, this is the timestamp of the swap transaction.
     */
    @Column(nullable = false)
    private Instant acquiredAt;

    /**
     * Original quantity acquired.
     */
    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal originalAmount;

    /**
     * Remaining quantity not yet disposed (for partial disposals).
     */
    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal remainingAmount;

    /**
     * Total cost basis in EUR for the original amount (inclusive of buy fees).
     */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal costBasisEur;

    /**
     * Foreign key to the Transaction that created this lot
     * (e.g., BUY, AIRDROP, SWAP_IN).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_transaction_id", nullable = false)
    private Transaction sourceTransaction;

    /**
     * Self-reference to the lot this was carried over from during a swap.
     * Null if this lot was not created by a swap or is the original lot in a chain.
     * Used to traverse the audit trail and determine original acquisition date for
     * the 2-year holding period exemption.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carried_over_from_lot_id")
    private TaxLot carriedOverFromLot;

    /**
     * Timestamp when this lot became fully disposed (remainingAmount = 0).
     * Null if lot still has remaining amount. Set by TaxLotService when
     * remainingAmount is reduced to zero via disposal or withdrawal fees.
     * Used to exclude closed lots from FIFO queries and for audit trail.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Record creation timestamp (audit trail).
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

