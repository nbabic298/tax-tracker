package io.codifica.tax_tracker.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Account domain entity representing a trading account on an exchange or broker.
 * Examples: "Kraken", "Coinbase", "Interactive Brokers".
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Name of the exchange or broker (e.g., "Kraken", "Coinbase", "IB").
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Account type or category (e.g., "exchange", "broker", "wallet").
     */
    @Column(nullable = false, length = 50)
    private String type;

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

