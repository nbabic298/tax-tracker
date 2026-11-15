package io.codifica.tax_tracker.infrastructure.binance;

import io.codifica.tax_tracker.domain.enrichment.PriceEnrichmentService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BinancePriceProperties.class)
public class EnrichmentConfig {

    @Bean
    public PriceEnrichmentService priceEnrichmentService(BinancePriceProvider binance) {
        return new PriceEnrichmentService(binance);
    }
}

