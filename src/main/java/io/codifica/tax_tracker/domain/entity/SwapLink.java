package io.codifica.tax_tracker.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * SwapLink domain entity linking the two legs of a crypto-to-crypto swap transaction.
 *
 * Per CLAUDE.md hard rules:
 * - Crypto-to-crypto swaps are NOT taxable events.
 * - They consume source-asset FIFO lots and assign the consumed lots' original
 *   acquisition date + cost basis to the target asset.
 *
 * SwapLink enables quick navigation from SWAP_OUT to SWAP_IN and vice versa.
 */
@Entity
@Table(name = "swap_link")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SwapLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key to the SWAP_OUT transaction (source asset being sold).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_transaction_id", nullable = false)
    private Transaction sourceTransaction;

    /**
     * Foreign key to the SWAP_IN transaction (target asset being acquired).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_transaction_id", nullable = false)
    private Transaction targetTransaction;

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

