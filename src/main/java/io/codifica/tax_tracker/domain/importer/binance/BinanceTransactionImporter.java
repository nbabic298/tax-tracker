package io.codifica.tax_tracker.domain.importer.binance;

import io.codifica.tax_tracker.domain.importer.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Binance transaction history CSV importer.
 *
 * Binance format (current):
 * User ID, Time, Account, Operation, Coin, Change, Remark
 *
 * Skips: Staking Redemption (internal transfer, no tax impact).
 * No price data in Binance CSV — unitPriceEur is always null.
 */
@Component
public class BinanceTransactionImporter implements TransactionImporter {

    private static final String IMPORTER_NAME = "BinanceTransactionImporter";
    private static final String BINANCE_FILENAME_PATTERN = "(?i).*binance.*transaction.*\\.csv";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId UTC_PLUS_1 = ZoneId.of("UTC+1");

    private static final String COL_USER_ID   = "User ID";
    private static final String COL_TIME      = "Time";
    private static final String COL_ACCOUNT   = "Account";
    private static final String COL_OPERATION = "Operation";
    private static final String COL_COIN      = "Coin";
    private static final String COL_CHANGE    = "Change";
    private static final String COL_REMARK    = "Remark";

    private static final String OP_DEPOSIT                     = "Deposit";
    private static final String OP_DEPOSIT_FIAT                = "Fiat Deposit";
    private static final String OP_WITHDRAW                    = "Withdraw";
    private static final String OP_STAKING_REWARDS             = "Staking Rewards";
    private static final String OP_STAKING_PURCHASE            = "Staking Purchase";
    private static final String OP_STAKING_REDEMPTION          = "Staking Redemption";
    private static final String OP_TRANSACTION_RELATED         = "Transaction Related";
    private static final String OP_BINANCE_CONVERT             = "Binance Convert";
    private static final String OP_SIMPLE_EARN_FLEXIBLE_INTEREST = "Simple Earn Flexible Interest";

    @Override
    public boolean supports(Path csvPath) {
        return csvPath.getFileName().toString().matches(BINANCE_FILENAME_PATTERN);
    }

    @Override
    public ImportResult importTransactions(Path csvPath) throws ImportException {
        try {
            return parse(csvPath);
        } catch (IOException e) {
            throw new ImportException("Failed to parse Binance CSV: " + csvPath, e);
        }
    }

    private ImportResult parse(Path csvPath) throws IOException {
        List<ParsedTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> unmatchedLegs = new ArrayList<>();

        // BOMInputStream strips the UTF-8 BOM that Binance prepends to its exports,
        // which would otherwise corrupt the first column header ("﻿User ID" vs "User ID").
        try (var bomStream = BOMInputStream.builder()
                .setInputStream(Files.newInputStream(csvPath))
                .get();
             var reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
             var csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build())) {

            List<CSVRecord> records = csvParser.getRecords();
            Set<Integer> processedIndices = new HashSet<>();

            for (int i = 0; i < records.size(); i++) {
                if (processedIndices.contains(i)) continue;

                CSVRecord record = records.get(i);
                String operation = get(record, COL_OPERATION);
                String coin = get(record, COL_COIN);

                // Skip staking-related internal transfers (no tax impact)
                if (OP_STAKING_REDEMPTION.equals(operation) || OP_STAKING_PURCHASE.equals(operation)) {
                    continue;
                }

                // Skip EUR deposits (account funding, not a taxable event)
                if (OP_DEPOSIT.equals(operation) || OP_DEPOSIT_FIAT.equals(operation)) {
                    continue;
                }

                if (OP_TRANSACTION_RELATED.equals(operation) || OP_BINANCE_CONVERT.equals(operation)) {
                    if (i + 1 < records.size()) {
                        CSVRecord next = records.get(i + 1);
                        String nextOp = get(next, COL_OPERATION);
                        if ((OP_TRANSACTION_RELATED.equals(nextOp) || OP_BINANCE_CONVERT.equals(nextOp)) &&
                                parseTimestamp(get(record, COL_TIME)).equals(parseTimestamp(get(next, COL_TIME))) &&
                                !get(record, COL_COIN).equals(get(next, COL_COIN))) {

                            // Two-leg transaction: pair EUR with asset and emit single BUY
                            parseTransactionRelatedPair(record, next, transactions, warnings);
                            processedIndices.add(i);
                            processedIndices.add(i + 1);
                            continue;
                        }
                    }
                    unmatchedLegs.add(rawLine(record));
                    warnings.add("Unmatched Transaction Related leg at row " + (i + 1) + ": " + rawLine(record));
                    continue;
                }

                ParsedTransaction tx = parseSingle(record, warnings);
                if (tx != null) transactions.add(tx);
            }
        }
        return new ImportResult(transactions, warnings, unmatchedLegs);
    }

    /**
     * Parses a Transaction Related or Binance Convert pair (EUR leg + asset leg at same timestamp).
     * Collapses them into a single BUY transaction with unitPriceEur calculated
     * from the EUR amount divided by the asset amount. The fiatAmount is set to
     * the absolute EUR amount from the EUR leg.
     *
     * Example:
     *   EUR -982, AXS +9.21968511  →  BUY 9.21968511 AXS at 106.52 EUR/AXS, amountFiat=982
     *   EUR -1999, SOL +21.30690013 (Binance Convert) → BUY 21.30690013 SOL at 93.88 EUR/SOL, amountFiat=1999
     */
    private void parseTransactionRelatedPair(CSVRecord eurRecord, CSVRecord assetRecord,
                                             List<ParsedTransaction> transactions,
                                             List<String> warnings) {
        String eurCoin = get(eurRecord, COL_COIN);
        String assetCoin = get(assetRecord, COL_COIN);
        BigDecimal eurChange = new BigDecimal(get(eurRecord, COL_CHANGE));
        BigDecimal assetChange = new BigDecimal(get(assetRecord, COL_CHANGE));

        // Determine which is which (EUR leg should have negative change, asset positive)
        if (!eurCoin.equals("EUR")) {
            // Swap if needed
            var tmpCoin = eurCoin;
            var tmpChange = eurChange;
            eurCoin = assetCoin;
            eurChange = assetChange;
            assetCoin = tmpCoin;
            assetChange = tmpChange;
        }

        Instant timestamp = parseTimestamp(get(eurRecord, COL_TIME));

        // Unit price = fiat spent / asset acquired
        // EUR change is negative (outgoing), so take absolute value
        BigDecimal eurSpent = eurChange.abs();
        BigDecimal assetAmount = assetChange.abs();
        // Maximum precision: scale 18, only round at DB persistence
        BigDecimal unitPrice = eurSpent.divide(assetAmount, 18, java.math.RoundingMode.HALF_UP);

        // Emit single BUY transaction for the asset
        // fiatAmount = the EUR amount spent (from the EUR leg)
        ParsedTransaction.Builder builder = ParsedTransaction.builder(IMPORTER_NAME, timestamp, assetCoin, assetAmount, "BUY")
                .unitPriceEur(unitPrice)
                .fiatAmount(eurSpent)
                .rawLine(rawLine(eurRecord) + " | " + rawLine(assetRecord));

        // Preserve fee info from either leg if present
        if (assetRecord.isMapped(COL_REMARK) && !get(assetRecord, COL_REMARK).isEmpty()) {
            // Fee details may be in the remark field; preserve them
        }

        transactions.add(builder.build());
    }

    private ParsedTransaction parseSingle(CSVRecord record, List<String> warnings) {
        String operation = get(record, COL_OPERATION);
        String coin      = get(record, COL_COIN);
        Instant ts       = parseTimestamp(get(record, COL_TIME));
        BigDecimal amount = new BigDecimal(get(record, COL_CHANGE)).abs();

        String type = switch (operation) {
            case OP_DEPOSIT,
                 OP_DEPOSIT_FIAT                  -> "TRANSFER_IN";
            case OP_WITHDRAW                      -> "TRANSFER_OUT";
            // Staking rewards and savings yield are income at FMV on receipt date,
            // NOT airdrops (cost basis = 0). Treat as STAKING_REWARD.
            case OP_STAKING_REWARDS,
                 OP_SIMPLE_EARN_FLEXIBLE_INTEREST -> "STAKING_REWARD";
            case OP_STAKING_PURCHASE              -> "TRANSFER_OUT";
            default                               -> null;
        };
        if (type == null) return null;

        warnings.add("Missing unitPriceEur for " + coin + " at " + ts + " (enrichment required)");
        return ParsedTransaction.builder(IMPORTER_NAME, ts, coin, amount, type)
                .rawLine(rawLine(record))
                .build();
    }

    private Instant parseTimestamp(String timeStr) {
        // Binance CSV timestamps are in UTC+1 (e.g. "2021-10-24 14:17:05")
        return LocalDateTime.parse(timeStr, TIMESTAMP_FORMATTER).atZone(UTC_PLUS_1).toInstant();
    }

    private String get(CSVRecord r, String col) { return r.get(col).trim(); }

    private String rawLine(CSVRecord record) {
        return String.join(",",
                get(record, COL_USER_ID), get(record, COL_TIME), get(record, COL_ACCOUNT),
                get(record, COL_OPERATION), get(record, COL_COIN),
                get(record, COL_CHANGE), get(record, COL_REMARK));
    }
}

