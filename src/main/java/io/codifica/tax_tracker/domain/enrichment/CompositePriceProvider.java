package io.codifica.tax_tracker.domain.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A {@link PriceProvider} that delegates to an ordered list of providers,
 * returning the first non-empty result.
 *
 * <p>This allows prioritising a cheap/fast source (Binance klines, which
 * already have precise EUR or USDT prices at the minute of the trade) before
 * falling back to a slower/rate-limited source (CoinGecko daily prices).
 *
 * <p>Provider order is significant: earlier providers are tried first.
 * If a provider throws, the exception is caught, logged as a warning, and
 * the next provider is tried. If all providers return empty or throw, the
 * composite returns {@link Optional#empty()}.
 */
public class CompositePriceProvider implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(CompositePriceProvider.class);

    private final List<PriceProvider> providers;

    public CompositePriceProvider(List<PriceProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one PriceProvider is required");
        }
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<BigDecimal> priceEur(String assetSymbol, Instant at) {
        for (PriceProvider provider : providers) {
            try {
                Optional<BigDecimal> result = provider.priceEur(assetSymbol, at);
                if (result.isPresent()) {
                    log.debug("Price for {} at {} resolved by {}: {}",
                            assetSymbol, at, provider.getClass().getSimpleName(), result.get());
                    return result;
                }
            } catch (Exception ex) {
                log.warn("Provider {} failed for {} at {}: {} — trying next provider",
                        provider.getClass().getSimpleName(), assetSymbol, at, ex.getMessage());
            }
        }
        log.debug("No price found for {} at {} from any provider", assetSymbol, at);
        return Optional.empty();
    }
}

