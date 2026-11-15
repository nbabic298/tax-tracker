package io.codifica.tax_tracker.domain.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Normalized parsed transaction from an exchange CSV.
 * This is the intermediate format before persistence to the database.
 *
 * Price enrichment is NOT applied here; unitPriceEur may be null (e.g. Binance)
 * but may also be populated directly from the source file (e.g. Bitpanda).
 */
public final class ParsedTransaction {

    private final String sourceImporter;
    private final Instant timestamp;
    private final String asset;
    private final BigDecimal amount;       // absolute value, always positive
    private final String type;             // BUY, SELL, SWAP_IN, SWAP_OUT, TRANSFER_IN, TRANSFER_OUT, AIRDROP
    private final BigDecimal unitPriceEur; // null if not available from source
    private final BigDecimal fiatAmount;   // EUR equivalent from source file (null if unavailable)
    private final BigDecimal feeAmount;    // fee quantity (null if unavailable)
    private final String feeAsset;         // fee denomination, e.g. "EUR", "ADA" (null if unavailable)
    private final BigDecimal spread;       // spread in fiat (null if unavailable; relevant for ETF/stocks)
    private final String externalId;       // source transaction ID; null for Binance
    private final String rawLine;          // original CSV line for debugging

    private ParsedTransaction(Builder builder) {
        this.sourceImporter = Objects.requireNonNull(builder.sourceImporter, "sourceImporter");
        this.timestamp      = Objects.requireNonNull(builder.timestamp, "timestamp");
        this.asset          = Objects.requireNonNull(builder.asset, "asset");
        this.amount         = Objects.requireNonNull(builder.amount, "amount");
        this.type           = Objects.requireNonNull(builder.type, "type");
        this.unitPriceEur   = builder.unitPriceEur;
        this.fiatAmount     = builder.fiatAmount;
        this.feeAmount      = builder.feeAmount;
        this.feeAsset       = builder.feeAsset;
        this.spread         = builder.spread;
        this.externalId     = builder.externalId;
        this.rawLine        = builder.rawLine;
    }

    public static Builder builder(String sourceImporter, Instant timestamp,
                                  String asset, BigDecimal amount, String type) {
        return new Builder(sourceImporter, timestamp, asset, amount, type);
    }

    /** Returns a Builder pre-populated with all fields from this instance. */
    public Builder toBuilder() {
        return new Builder(sourceImporter, timestamp, asset, amount, type)
                .unitPriceEur(unitPriceEur)
                .fiatAmount(fiatAmount)
                .feeAmount(feeAmount)
                .feeAsset(feeAsset)
                .spread(spread)
                .externalId(externalId)
                .rawLine(rawLine);
    }

    /** Convenience: returns a new immutable instance with unitPriceEur set. */
    public ParsedTransaction withUnitPriceEur(BigDecimal price) {
        return toBuilder().unitPriceEur(price).build();
    }

    public String getSourceImporter() { return sourceImporter; }
    public Instant getTimestamp()     { return timestamp; }
    public String getAsset()          { return asset; }
    public BigDecimal getAmount()     { return amount; }
    public String getType()           { return type; }
    public BigDecimal getUnitPriceEur() { return unitPriceEur; }
    public BigDecimal getFiatAmount() { return fiatAmount; }
    public BigDecimal getFeeAmount()  { return feeAmount; }
    public String getFeeAsset()       { return feeAsset; }
    public BigDecimal getSpread()     { return spread; }
    public String getExternalId()     { return externalId; }
    public String getRawLine()        { return rawLine; }

    @Override
    public String toString() {
        return "ParsedTransaction{" +
                "sourceImporter='" + sourceImporter + '\'' +
                ", timestamp=" + timestamp +
                ", asset='" + asset + '\'' +
                ", amount=" + amount +
                ", type='" + type + '\'' +
                ", unitPriceEur=" + unitPriceEur +
                ", fiatAmount=" + fiatAmount +
                ", feeAmount=" + feeAmount +
                ", feeAsset='" + feeAsset + '\'' +
                ", spread=" + spread +
                ", externalId='" + externalId + '\'' +
                '}';
    }

    public static final class Builder {
        private final String sourceImporter;
        private final Instant timestamp;
        private final String asset;
        private final BigDecimal amount;
        private final String type;
        private BigDecimal unitPriceEur;
        private BigDecimal fiatAmount;
        private BigDecimal feeAmount;
        private String feeAsset;
        private BigDecimal spread;
        private String externalId;
        private String rawLine;

        private Builder(String sourceImporter, Instant timestamp,
                        String asset, BigDecimal amount, String type) {
            this.sourceImporter = sourceImporter;
            this.timestamp      = timestamp;
            this.asset          = asset;
            this.amount         = amount;
            this.type           = type;
        }

        public Builder unitPriceEur(BigDecimal v) { this.unitPriceEur = v; return this; }
        public Builder fiatAmount(BigDecimal v)   { this.fiatAmount = v; return this; }
        public Builder feeAmount(BigDecimal v)    { this.feeAmount = v; return this; }
        public Builder feeAsset(String v)         { this.feeAsset = v; return this; }
        public Builder spread(BigDecimal v)       { this.spread = v; return this; }
        public Builder externalId(String v)       { this.externalId = v; return this; }
        public Builder rawLine(String v)          { this.rawLine = v; return this; }
        public ParsedTransaction build()          { return new ParsedTransaction(this); }
    }
}
