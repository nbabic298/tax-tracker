package io.codifica.tax_tracker.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Disposal domain entity representing the sale (or other disposition) of asset units from a TaxLot.
 * Captures the gain/loss and tracks the tax year for Croatian netting rules.
 *
 * Per CLAUDE.md hard rules:
 * - Netting: losses offset gains within the same calendar year only.
 * - If holding period > 2 years from original acquisition => gain is tax-exempt.
 */
@Entity
@Table(name = "disposal", indexes = {
        @Index(name = "idx_disposal_lot_id", columnList = "lot_id"),
        @Index(name = "idx_disposal_tax_year", columnList = "tax_year")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Disposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key to the TaxLot being disposed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id", nullable = false)
    private TaxLot lot;

    /**
     * Business disposition date (e.g., sale execution time).
     */
    @Column(nullable = false)
    private Instant disposedAt;

    /**
     * Quantity disposed from the lot.
     */
    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal amount;

    /**
     * Total proceeds in EUR (sale price × quantity - sell fees).
     */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal proceedsEur;

    /**
     * Cost basis in EUR for this disposal (allocated pro-rata from the lot's cost basis).
     */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal costBasisEur;

    /**
     * Realized gain in EUR (proceeds - cost basis).
     * Can be negative (loss).
     */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal gainEur;

    /**
     * Whether this gain is tax-exempt (true if holding period > 2 years).
     */
    @Column(nullable = false)
    private Boolean isExempt;

    /**
     * Tax year of disposal (for annual netting and reporting).
     */
    @Column(nullable = false)
    private Integer taxYear;

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

