package io.codifica.tax_tracker.domain.importer.bitpanda;

import io.codifica.tax_tracker.domain.importer.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bitpanda transaction history CSV importer.
 *
 * Format (semicolon-delimited, 18 columns):
 * "Transaction ID";Timestamp;"Transaction Type";In/Out;"Amount Fiat";Fiat;
 * "Amount Asset";Asset;"Asset market price";"Asset market price currency";
 * "Asset class";"Product ID";Fee;"Fee asset";"Fee percent";
 * Spread;"Spread Currency";"Tax Fiat"
 *
 * <h2>Transaction type mapping:</h2>
 * <ul>
 *   <li>buy     → BUY (single row; EUR price always present)</li>
 *   <li>sell    → SELL (single row)</li>
 *   <li>deposit (crypto) → TRANSFER_IN; deposit (fiat) → skipped</li>
 *   <li>withdrawal → TRANSFER_OUT</li>
 *   <li>transfer incoming (crypto) → TRANSFER_IN; (fiat) → skipped</li>
 *   <li>transfer outgoing (crypto) → TRANSFER_OUT</li>
 *   <li>transfer(stake) → skipped (internal, no tax impact)</li>
 *   <li>transfer(unstake) → skipped (internal, no tax impact)</li>
 *   <li>reward  → AIRDROP</li>
 * </ul>
 *
 * <h2>Swap detection:</h2>
 * Rows sharing the same Transaction ID prefix (text before the first '-') form
 * one logical order. Within a prefix group, a sell+buy pair of two non-BCPEUR
 * assets at the same timestamp is emitted as SELL+BUY (the caller is responsible
 * for creating the SwapLink). BCPEUR rows are skipped because BCPEUR is
 * Bitpanda's internal EUR cash token — it does not constitute a separate asset
 * acquisition or disposal.
 */
public class BitpandaTransactionImporter implements TransactionImporter {

    static final String IMPORTER_NAME = "BitpandaTransactionImporter";

    private static final String BITPANDA_FILENAME_PATTERN = "(?i).*bitpanda.*\\.csv";

    // Columns
    private static final String COL_ID           = "Transaction ID";
    private static final String COL_TIMESTAMP    = "Timestamp";
    private static final String COL_TYPE         = "Transaction Type";
    private static final String COL_DIRECTION    = "In/Out";
    private static final String COL_AMOUNT_FIAT  = "Amount Fiat";
    private static final String COL_FIAT         = "Fiat";
    private static final String COL_AMOUNT_ASSET = "Amount Asset";
    private static final String COL_ASSET        = "Asset";
    private static final String COL_PRICE        = "Asset market price";
    private static final String COL_PRICE_CCY    = "Asset market price currency";
    private static final String COL_ASSET_CLASS  = "Asset class";
    private static final String COL_FEE          = "Fee";
    private static final String COL_FEE_ASSET    = "Fee asset";
    private static final String COL_SPREAD       = "Spread";
    private static final String COL_SPREAD_CCY   = "Spread Currency";

    // Transaction types as they appear in the CSV
    private static final String TYPE_BUY              = "buy";
    private static final String TYPE_SELL             = "sell";
    private static final String TYPE_DEPOSIT          = "deposit";
    private static final String TYPE_WITHDRAWAL       = "withdrawal";
    private static final String TYPE_TRANSFER         = "transfer";
    private static final String TYPE_TRANSFER_STAKE   = "transfer(stake)";
    private static final String TYPE_TRANSFER_UNSTAKE = "transfer(unstake)";
    private static final String TYPE_REWARD           = "reward";

    /** Bitpanda's internal EUR cash token — treated as transparent pass-through. */
    private static final String BCPEUR = "BCPEUR";
    private static final String FIAT_ASSET_CLASS = "Fiat";
    private static final String MISSING = "-";

    @Override
    public boolean supports(Path csvPath) {
        return csvPath.getFileName().toString().matches(BITPANDA_FILENAME_PATTERN);
    }

    @Override
    public ImportResult importTransactions(Path csvPath) throws ImportException {
        try {
            return parse(csvPath);
        } catch (IOException e) {
            throw new ImportException("Failed to parse Bitpanda CSV: " + csvPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Core parse logic
    // -------------------------------------------------------------------------

    private ImportResult parse(Path csvPath) throws IOException {
        List<ParsedTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> unmatchedLegs = new ArrayList<>();

        List<CSVRecord> records = readRecords(csvPath);

        // Group rows by Transaction ID prefix (text before first '-').
        // LinkedHashMap preserves file order across groups.
        Map<String, List<CSVRecord>> groups = records.stream()
                .collect(Collectors.groupingBy(
                        r -> idPrefix(get(r, COL_ID)),
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<String, List<CSVRecord>> entry : groups.entrySet()) {
            processGroup(entry.getKey(), entry.getValue(), transactions, warnings, unmatchedLegs);
        }

        return new ImportResult(transactions, warnings, unmatchedLegs);
    }

    private List<CSVRecord> readRecords(Path csvPath) throws IOException {
        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             var parser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withDelimiter(';')
                     .withHeader()
                     .withTrim()
                     .withQuote('"'))) {
            return parser.getRecords();
        }
    }

    // -------------------------------------------------------------------------
    // Group processing
    // -------------------------------------------------------------------------

    private void processGroup(String prefix, List<CSVRecord> group,
                              List<ParsedTransaction> out,
                              List<String> warnings,
                              List<String> unmatchedLegs) {

        // Skip all-BCPEUR and all-fiat groups silently
        if (group.stream().allMatch(r -> isBcpeur(r) || isFiatRow(r))) return;

        // Single row
        if (group.size() == 1) {
            processSingleRow(group.get(0), out, warnings);
            return;
        }

        // Multi-row group: separate into real-asset rows and passthrough rows
        List<CSVRecord> real = group.stream()
                .filter(r -> !isBcpeur(r) && !isFiatRow(r) && !isStakeTransfer(r))
                .toList();
        List<CSVRecord> ignored = group.stream()
                .filter(r -> isBcpeur(r) || isFiatRow(r) || isStakeTransfer(r))
                .toList();

        if (real.isEmpty()) return; // only internal rows

        // Check for sell+buy swap pairs within real rows (same timestamp)
        // Pull out sells and buys; pair them positionally (Bitpanda lists them in order)
        List<CSVRecord> sells = real.stream().filter(r -> TYPE_SELL.equals(get(r, COL_TYPE))).toList();
        List<CSVRecord> buys  = real.stream().filter(r -> TYPE_BUY.equals(get(r, COL_TYPE))).toList();
        List<CSVRecord> other = real.stream()
                .filter(r -> !TYPE_SELL.equals(get(r, COL_TYPE)) && !TYPE_BUY.equals(get(r, COL_TYPE)))
                .toList();

        // Pair sells with buys
        int pairs = Math.min(sells.size(), buys.size());
        for (int i = 0; i < pairs; i++) {
            emitSwapPair(sells.get(i), buys.get(i), out, warnings);
        }
        // Excess sells or buys (unmatched)
        for (int i = pairs; i < sells.size(); i++) {
            processSingleRow(sells.get(i), out, warnings);
        }
        for (int i = pairs; i < buys.size(); i++) {
            processSingleRow(buys.get(i), out, warnings);
        }
        // Non-trade rows within the group
        for (CSVRecord r : other) {
            processSingleRow(r, out, warnings);
        }
        // Also process any reward/transfer rows that share an ID prefix with a trade
        // (e.g. the BEST cashback attached to some Bitpanda trades)
        for (CSVRecord r : ignored) {
            if (isReward(r)) processSingleRow(r, out, warnings);
        }
    }

    // -------------------------------------------------------------------------
    // Single-row dispatch
    // -------------------------------------------------------------------------

    private void processSingleRow(CSVRecord record, List<ParsedTransaction> out, List<String> warnings) {
        String type = get(record, COL_TYPE);
        switch (type) {
            case TYPE_BUY      -> emitBuy(record, out);
            case TYPE_SELL     -> emitSell(record, out);
            case TYPE_DEPOSIT  -> emitDeposit(record, out, warnings);
            case TYPE_WITHDRAWAL -> emitWithdrawal(record, out);
            case TYPE_TRANSFER   -> emitTransfer(record, out);
            case TYPE_REWARD     -> emitReward(record, out);
            case TYPE_TRANSFER_STAKE, TYPE_TRANSFER_UNSTAKE -> { /* internal, no tax impact */ }
            default -> warnings.add("Unrecognised Bitpanda transaction type '" + type
                    + "' at " + get(record, COL_TIMESTAMP) + " — skipped");
        }
    }

    // -------------------------------------------------------------------------
    // Emitters
    // -------------------------------------------------------------------------

    private void emitSwapPair(CSVRecord sellRow, CSVRecord buyRow,
                              List<ParsedTransaction> out, List<String> warnings) {
        // Both legs already have market prices in EUR — no enrichment warning needed.
        out.add(buildCommon(sellRow, "SELL").build());
        out.add(buildCommon(buyRow, "BUY").build());
    }

    private void emitBuy(CSVRecord r, List<ParsedTransaction> out) {
        out.add(buildCommon(r, "BUY").build());
    }

    private void emitSell(CSVRecord r, List<ParsedTransaction> out) {
        out.add(buildCommon(r, "SELL").build());
    }

    private void emitDeposit(CSVRecord r, List<ParsedTransaction> out, List<String> warnings) {
        if (isFiatRow(r)) return; // pure EUR deposit — no asset acquisition
        out.add(buildCommon(r, "TRANSFER_IN").build());
    }

    private void emitWithdrawal(CSVRecord r, List<ParsedTransaction> out) {
        out.add(buildCommon(r, "TRANSFER_OUT").build());
    }

    private void emitTransfer(CSVRecord r, List<ParsedTransaction> out) {
        if (isFiatRow(r)) return;
        String direction = get(r, COL_DIRECTION);
        String domainType = "incoming".equals(direction) ? "TRANSFER_IN" : "TRANSFER_OUT";
        out.add(buildCommon(r, domainType).build());
    }

    private void emitReward(CSVRecord r, List<ParsedTransaction> out) {
        // Bitpanda "reward" = ETH/crypto staking yield — income at FMV on receipt,
        // NOT a zero-cost-basis airdrop.
        out.add(buildCommon(r, "STAKING_REWARD").build());
    }

    // -------------------------------------------------------------------------
    // Builder helpers
    // -------------------------------------------------------------------------

    /**
     * Populates all fields that every Bitpanda row provides.
     */
    private ParsedTransaction.Builder buildCommon(CSVRecord r, String domainType) {
        Instant ts        = parseTimestamp(get(r, COL_TIMESTAMP));
        String asset      = get(r, COL_ASSET);
        BigDecimal amount = parseBigDecimal(get(r, COL_AMOUNT_ASSET));
        BigDecimal price  = parseBigDecimal(get(r, COL_PRICE));
        BigDecimal fiatAmt = parseBigDecimal(get(r, COL_AMOUNT_FIAT));
        BigDecimal fee    = parseBigDecimal(get(r, COL_FEE));
        String feeAsset   = nullIfMissing(get(r, COL_FEE_ASSET));
        BigDecimal spread = parseBigDecimal(get(r, COL_SPREAD));
        String externalId = nullIfMissing(get(r, COL_ID));

        return ParsedTransaction.builder(IMPORTER_NAME, ts, asset, amount, domainType)
                .unitPriceEur(price)
                .fiatAmount(fiatAmt)
                .feeAmount(fee)
                .feeAsset(feeAsset)
                .spread(spread)
                .externalId(externalId)
                .rawLine(rawLine(r));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Extract the ID prefix: everything before the first '-'. */
    static String idPrefix(String id) {
        int idx = id.indexOf('-');
        return idx < 0 ? id : id.substring(0, idx);
    }

    private Instant parseTimestamp(String ts) {
        return OffsetDateTime.parse(ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }

    /**
     * Parse a BigDecimal from a Bitpanda field value.
     * Returns null for missing ("-") or empty values.
     */
    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank() || MISSING.equals(val)) return null;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullIfMissing(String val) {
        return (val == null || val.isBlank() || MISSING.equals(val)) ? null : val;
    }

    private boolean isBcpeur(CSVRecord r) {
        return BCPEUR.equals(get(r, COL_ASSET));
    }

    private boolean isFiatRow(CSVRecord r) {
        return FIAT_ASSET_CLASS.equals(get(r, COL_ASSET_CLASS))
                || "EUR".equals(get(r, COL_ASSET));
    }

    private boolean isStakeTransfer(CSVRecord r) {
        String t = get(r, COL_TYPE);
        return TYPE_TRANSFER_STAKE.equals(t) || TYPE_TRANSFER_UNSTAKE.equals(t);
    }

    private boolean isReward(CSVRecord r) {
        return TYPE_REWARD.equals(get(r, COL_TYPE));
    }

    private String get(CSVRecord r, String col) {
        try { return r.get(col).trim(); }
        catch (IllegalArgumentException e) { return ""; }
    }

    private String rawLine(CSVRecord r) {
        return r.toMap().values().stream()
                .map(v -> v == null ? "" : v)
                .collect(Collectors.joining(";"));
    }
}


