package io.codifica.tax_tracker.domain.enrichment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CompositePriceProvider")
class CompositePriceProviderTest {

    private static final Instant T = Instant.parse("2021-10-24T14:17:05Z");

    private PriceProvider fixed(String symbol, BigDecimal price) {
        return (s, at) -> s.equals(symbol) ? Optional.of(price) : Optional.empty();
    }

    private PriceProvider alwaysEmpty() {
        return (s, at) -> Optional.empty();
    }

    private PriceProvider alwaysThrows(String msg) {
        return (s, at) -> { throw new RuntimeException(msg); };
    }

    @Test
    @DisplayName("Returns result from first provider when it has a price")
    void returnsFirstProviderResult() {
        var composite = new CompositePriceProvider(List.of(
                fixed("BTC", new BigDecimal("27000")),
                fixed("BTC", new BigDecimal("99999"))   // should not be reached
        ));

        var result = composite.priceEur("BTC", T);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("27000").compareTo(result.get()));
    }

    @Test
    @DisplayName("Falls back to second provider when first returns empty")
    void fallsBackToSecondProvider() {
        var composite = new CompositePriceProvider(List.of(
                alwaysEmpty(),
                fixed("BTC", new BigDecimal("27000"))
        ));

        var result = composite.priceEur("BTC", T);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("27000").compareTo(result.get()));
    }

    @Test
    @DisplayName("Falls back to next provider when earlier provider throws")
    void fallsBackOnException() {
        var composite = new CompositePriceProvider(List.of(
                alwaysThrows("HTTP 429"),
                fixed("BTC", new BigDecimal("27000"))
        ));

        var result = composite.priceEur("BTC", T);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("27000").compareTo(result.get()));
    }

    @Test
    @DisplayName("Returns empty when all providers return empty")
    void returnsEmptyWhenAllEmpty() {
        var composite = new CompositePriceProvider(List.of(alwaysEmpty(), alwaysEmpty()));
        assertTrue(composite.priceEur("BTC", T).isEmpty());
    }

    @Test
    @DisplayName("Returns empty when all providers throw")
    void returnsEmptyWhenAllThrow() {
        var composite = new CompositePriceProvider(List.of(
                alwaysThrows("error 1"),
                alwaysThrows("error 2")
        ));
        assertTrue(composite.priceEur("BTC", T).isEmpty());
    }

    @Test
    @DisplayName("Single provider: returns its result directly")
    void singleProvider() {
        var composite = new CompositePriceProvider(List.of(fixed("ETH", new BigDecimal("1800"))));
        assertEquals(0, new BigDecimal("1800").compareTo(composite.priceEur("ETH", T).get()));
    }

    @Test
    @DisplayName("Constructor rejects empty provider list")
    void rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new CompositePriceProvider(List.of()));
    }

    @Test
    @DisplayName("Constructor rejects null provider list")
    void rejectsNullList() {
        assertThrows(IllegalArgumentException.class, () -> new CompositePriceProvider(null));
    }

    @Test
    @DisplayName("Different symbols are routed to correct provider")
    void routesBySymbol() {
        var composite = new CompositePriceProvider(List.of(
                fixed("BTC", new BigDecimal("27000")),
                fixed("ETH", new BigDecimal("1800"))
        ));

        // BTC resolved by provider 1
        assertEquals(0, new BigDecimal("27000").compareTo(composite.priceEur("BTC", T).get()));
        // ETH: provider 1 returns empty, provider 2 returns 1800
        assertEquals(0, new BigDecimal("1800").compareTo(composite.priceEur("ETH", T).get()));
        // ADA: neither has it
        assertTrue(composite.priceEur("ADA", T).isEmpty());
    }
}

