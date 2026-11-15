package io.codifica.tax_tracker.domain.enrichment;

import java.util.Collections;
import java.util.List;

/**
 * Summary of a single {@link PriceEnrichmentService#enrich} call.
 */
public final class EnrichmentReport {

    private final int enrichedCount;
    private final int stillMissingCount;
    private final List<String> failures;  // e.g. "BTC@2021-10-24: HTTP 429 from CoinGecko"

    public EnrichmentReport(int enrichedCount, int stillMissingCount, List<String> failures) {
        this.enrichedCount    = enrichedCount;
        this.stillMissingCount = stillMissingCount;
        this.failures         = Collections.unmodifiableList(List.copyOf(failures));
    }

    public int getEnrichedCount()     { return enrichedCount; }
    public int getStillMissingCount() { return stillMissingCount; }
    public List<String> getFailures() { return failures; }

    @Override
    public String toString() {
        return "EnrichmentReport{enriched=" + enrichedCount
                + ", stillMissing=" + stillMissingCount
                + ", failures=" + failures.size() + "}";
    }
}

