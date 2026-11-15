package io.codifica.tax_tracker.domain.enum_types;

/**
 * Asset type classification as per CLAUDE.md requirements.
 * Extended to include Bitpanda's precious metal and commodity products.
 */
public enum AssetType {
    CRYPTO,
    STOCK,
    ETF,
    TOKENIZED_STOCK,
    METAL,
    COMMODITY
}
