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
 * Binance withdraw history CSV importer.
 *
 * Format (current):
 * Time, Coin, Network, Amount, Fee, Address, TXID, Status
 *
 * Only imports completed withdrawals. Fee is captured for cost basis adjustment.
 * Each withdrawal becomes a TRANSFER_OUT transaction with the fee recorded.
 */
@Component
public class BinanceWithdrawImporter implements TransactionImporter {

    private static final String IMPORTER_NAME = "BinanceWithdrawImporter";
    private static final String BINANCE_WITHDRAW_PATTERN = "(?i).*binance.*withdraw.*\\.csv";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId UTC_PLUS_1 = ZoneId.of("UTC+1");

    private static final String COL_TIME = "Time";
    private static final String COL_COIN = "Coin";
    private static final String COL_NETWORK = "Network";
    private static final String COL_AMOUNT = "Amount";
    private static final String COL_FEE = "Fee";
    private static final String COL_ADDRESS = "Address";
    private static final String COL_TXID = "TXID";
    private static final String COL_STATUS = "Status";

    private static final String STATUS_COMPLETED = "Completed";

    @Override
    public boolean supports(Path csvPath) {
        return csvPath.getFileName().toString().matches(BINANCE_WITHDRAW_PATTERN);
    }

    @Override
    public ImportResult importTransactions(Path csvPath) throws ImportException {
        try {
            return parse(csvPath);
        } catch (IOException e) {
            throw new ImportException("Failed to parse Binance withdraw CSV: " + csvPath, e);
        }
    }

    private ImportResult parse(Path csvPath) throws IOException {
        List<ParsedTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> unmatchedLegs = new ArrayList<>();

        // BOMInputStream strips UTF-8 BOM that Binance prepends
        try (var bomStream = BOMInputStream.builder()
                .setInputStream(Files.newInputStream(csvPath))
                .get();
             var reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
             var csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build())) {

            for (CSVRecord record : csvParser) {
                String status = get(record, COL_STATUS);

                // Only import completed withdrawals
                if (!STATUS_COMPLETED.equals(status)) {
                    continue;
                }

                ParsedTransaction tx = parseWithdrawal(record, warnings);
                if (tx != null) {
                    transactions.add(tx);
                }
            }
        }

        return new ImportResult(
                transactions,
                warnings,
                unmatchedLegs
        );
    }

    /**
     * Parses a single completed withdrawal row.
     *
     * Withdrawal is modeled as TRANSFER_OUT with:
     * - amount = total withdrawn (including fee)
     * - feeAmount = fee in the withdrawal asset
     * - feeAsset = the withdrawn asset (fees are paid in the same asset)
     * - unitPriceEur = null (TBD by enrichment if needed)
     *
     * Example:
     *   SOL withdrawal: amount=1.15622567, fee=0.008 SOL
     *   → TRANSFER_OUT 1.15622567 SOL with feeAmount=0.008, feeAsset=SOL
     */
    private ParsedTransaction parseWithdrawal(CSVRecord record, List<String> warnings) {
        String coin = get(record, COL_COIN);
        String timeStr = get(record, COL_TIME);
        String amountStr = get(record, COL_AMOUNT);
        String feeStr = get(record, COL_FEE);

        try {
            Instant timestamp = parseTimestamp(timeStr);
            BigDecimal amount = new BigDecimal(amountStr);
            BigDecimal fee = new BigDecimal(feeStr);

            // Build the raw line for audit trail
            String rawLine = String.join(",",
                    get(record, COL_TIME), get(record, COL_COIN), get(record, COL_NETWORK),
                    get(record, COL_AMOUNT), get(record, COL_FEE),
                    get(record, COL_ADDRESS), get(record, COL_TXID), get(record, COL_STATUS));

            return ParsedTransaction.builder(IMPORTER_NAME, timestamp, coin, amount, "TRANSFER_OUT")
                    .feeAmount(fee)
                    .feeAsset(coin)  // Withdrawal fees are in the same asset
                    .rawLine(rawLine)
                    .build();
        } catch (NumberFormatException e) {
            warnings.add("Failed to parse withdrawal row for " + coin + " at " + timeStr + ": " + e.getMessage());
            return null;
        }
    }

    private Instant parseTimestamp(String timeStr) {
        // Binance withdraw CSV timestamps are in UTC+1
        return LocalDateTime.parse(timeStr, TIMESTAMP_FORMATTER).atZone(UTC_PLUS_1).toInstant();
    }

    private String get(CSVRecord record, String col) {
        return record.get(col).trim();
    }
}


