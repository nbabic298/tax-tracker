package io.codifica.tax_tracker.domain.entity;

import io.codifica.tax_tracker.domain.enum_types.TransactionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaction domain entity representing a single taxable or non-taxable event
 * (buy, sell, swap, airdrop, dividend, fee, transfer).
 *
 * Money is stored in EUR with BigDecimal for precision.
 * rawJson stores the original exchange API response for audit/re-import.
 */
@Entity
@Table(name = "transaction")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key to Account.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Foreign key to Asset.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /**
     * Business timestamp of the transaction (e.g., exchange execution time).
     */
    @Column(nullable = false)
    private Instant timestamp;

    /**
     * Type of transaction (BUY, SELL, SWAP_IN, SWAP_OUT, AIRDROP, DIVIDEND, FEE, TRANSFER_IN, TRANSFER_OUT).
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionType type;

    /**
     * Amount of asset involved (quantity).
     * Scale 18 for maximum precision (satoshis, wei, etc).
     */
    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal amount;

    /**
     * Unit price in EUR per asset unit (may be null for transfers or airdrops).
     * Scale 18 for maximum precision across all price types.
     */
    @Column(precision = 30, scale = 18)
    private BigDecimal unitPriceEur;

    /**
     * Total EUR value of the transaction.
     * For BUY: cost in EUR. If feeEur is not null, it's included in amountFiat.
     * For SELL: proceeds in EUR. If feeEur is not null, it's included in amountFiat.
     * When feeEur is null, the fee is already accounted for in amountFiat.
     * Null for non-priced transactions (transfers, etc).
     * Scale 18 for maximum precision.
     */
    @Column(precision = 30, scale = 18)
    private BigDecimal amountFiat;

    /**
     * Fee amount (quantity in the fee asset).
     * Example: 0.001 BTC, 2.5 EUR, etc.
     * Null if no fee.
     * Scale 18 for maximum precision.
     */
    @Column(precision = 30, scale = 18)
    private BigDecimal feeAmount;

    /**
     * Fee denomination/asset (e.g. "BTC", "EUR", "ADA").
     * Null if no fee.
     */
    @Column(length = 20)
    private String feeAsset;

    /**
     * Fee in EUR. For buys: added to cost basis. For sells: subtracted from proceeds.
     * Scale 18 for maximum precision.
     */
    @Column(precision = 30, scale = 18)
    private BigDecimal feeEur;

    /** External identifier from exchange/broker. Unique per account. */
    @Column(length = 100)
    private String externalId;

    /** Source file name for audit trail. */
    @Column(length = 255)
    private String sourceFile;

    /**
     * Raw CSV line from the source import file, stored for audit and re-import.
     */
    @Column(columnDefinition = "text")
    private String rawJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

