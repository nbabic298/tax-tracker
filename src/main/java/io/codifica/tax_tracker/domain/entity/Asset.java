package io.codifica.tax_tracker.domain.entity;

import io.codifica.tax_tracker.domain.enum_types.AssetType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Asset domain entity representing crypto, stocks, ETFs, or tokenized stocks.
 * Holds canonical reference data for assets used in transactions.
 */
@Entity
@Table(name = "asset")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Ticker symbol (e.g., "BTC", "AAPL", "SPY").
     */
    @Column(nullable = false, length = 50)
    private String symbol;

    /**
     * Human-readable name (e.g., "Bitcoin", "Apple Inc.", "SPDR S&P 500 ETF").
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Asset type classification.
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AssetType assetType;

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

