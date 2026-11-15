package io.codifica.tax_tracker.infrastructure.binance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binance market-data API configuration.
 * Bind via {@code binance.price.*} in application.yaml.
 *
 * Example:
 * <pre>
 * binance:
 *   price:
 *     base-url: https://api.binance.com
 *     requests-per-minute: 1200   # public endpoint limit
 * </pre>
 *
 * The Binance klines endpoint is public — no API key required.
 */
@ConfigurationProperties(prefix = "binance.price")
public class BinancePriceProperties {

    /**
     * Binance REST API base URL.
     * Override to point at a mock server in tests or the testnet.
     */
    private String baseUrl = "https://api.binance.com";

    /**
     * Maximum requests per minute.
     * Binance public endpoints allow 1,200 request-weight per minute;
     * each klines call costs 2 weight, so the safe ceiling is 600 rpm.
     * We default to 300 to leave comfortable headroom.
     */
    private int requestsPerMinute = 300;

    public String getBaseUrl()                { return baseUrl; }
    public void setBaseUrl(String v)          { this.baseUrl = v; }

    public int getRequestsPerMinute()         { return requestsPerMinute; }
    public void setRequestsPerMinute(int v)   { this.requestsPerMinute = v; }
}

