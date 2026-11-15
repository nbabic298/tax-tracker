package io.codifica.tax_tracker.domain.enrichment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Pluggable price source. Implementations may call external APIs (CoinGecko,
 * ECB, etc.) or return pre-loaded data for testing.
 *
 * Contract:
 * - Returns the EUR price for one unit of {@code assetSymbol} at the given moment.
 * - Returns {@link Optional#empty()} if no price is available (unknown symbol,
 *   date too old, rate-limit exhausted, etc.).
 * - Implementations are responsible for caching and rate-limiting.
 */
public interface PriceProvider {

    /**
     * @param assetSymbol  ticker symbol, e.g. "BTC", "ETH", "USDT"
     * @param at           point in time for the historical price lookup
     * @return             EUR price per unit, or empty if unavailable
     */
    Optional<BigDecimal> priceEur(String assetSymbol, Instant at);
}

