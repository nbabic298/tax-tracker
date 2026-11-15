package io.codifica.tax_tracker.domain.importer.binance;

import io.codifica.tax_tracker.domain.importer.ImportException;
import io.codifica.tax_tracker.domain.importer.ImportResult;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BinanceTransactionImporter")
class BinanceTransactionImporterTest {

    private BinanceTransactionImporter importer;
    private Path resources;

    @BeforeEach
    void setUp() {
        importer  = new BinanceTransactionImporter();
        resources = Paths.get("src/test/resources");
    }

    @Test
    @DisplayName("supports() returns true for Binance CSV files")
    void testSupportsFilePattern() {
        assertTrue(importer.supports(Paths.get("Binance-Transaction-History-202611131053.csv")));
        assertTrue(importer.supports(Paths.get("binance_transactions.csv")));
        assertTrue(importer.supports(Paths.get("binance-transaction-history-2024.csv")));
        assertFalse(importer.supports(Paths.get("BINANCE_EXPORT.csv")));  // no 'transaction' in name
        assertFalse(importer.supports(Paths.get("kraken_export.csv")));
        assertFalse(importer.supports(Paths.get("trades.csv")));
    }

    @Test
    @DisplayName("Simple buy: Deposit + Transaction Related pair (EUR/-AXS) → TRANSFER_IN + single BUY with unitPriceEur calculated")
    void testParseBuyTrade() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_trade_buy.csv"));

        assertEquals(2, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        ParsedTransaction deposit = result.getTransactions().get(0);
        assertEquals("TRANSFER_IN", deposit.getType());
        assertEquals("EUR",         deposit.getAsset());
        assertEquals(0, new BigDecimal("982").compareTo(deposit.getAmount()));
        assertNull(deposit.getUnitPriceEur());

        // Transaction Related pair collapsed into single BUY with calculated price
        // 982 EUR / 9.21968511 AXS = 106.52 EUR/AXS
        ParsedTransaction buy = result.getTransactions().get(1);
        assertEquals("BUY", buy.getType());
        assertEquals("AXS", buy.getAsset());
        assertEquals(0, new BigDecimal("9.21968511").compareTo(buy.getAmount()));
        assertEquals(0, new BigDecimal("106.52272345").compareTo(buy.getUnitPriceEur()));
    }

    @Test
    @DisplayName("Simple sell: Transaction Related pair (ADA/-EUR) → single BUY with calculated unitPriceEur")
    void testParseSellTrade() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_trade_sell.csv"));

        assertEquals(2, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        // Transaction Related pair: ADA out, EUR in → collapsed to single BUY
        // 982.49 EUR / 653.995005 ADA = 1.50226... EUR/ADA
        ParsedTransaction buy = result.getTransactions().get(1);
        assertEquals("BUY", buy.getType());
        assertEquals("ADA", buy.getAsset());
        assertEquals(0, new BigDecimal("653.995005").compareTo(buy.getAmount()));
        assertEquals(0, new BigDecimal("1.50226265").compareTo(buy.getUnitPriceEur()));
    }

    @Test
    @DisplayName("Swap: two Transaction Related pairs (BTC/-USDT + USDT/-BTC) → two single BUY transactions with calculated prices")
    void testParseSwap() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_swap_btc_to_eth.csv"));

        assertEquals(2, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        // Pair 1: BTC out + USDT in → collapsed to BUY USDT at unitPrice = BTC amount / USDT amount
        // Pair 2: USDT out + ETH in → collapsed to BUY ETH at unitPrice = USDT amount / ETH amount
        ParsedTransaction first = result.getTransactions().get(0);
        ParsedTransaction second = result.getTransactions().get(1);

        // Both should be BUY type
        assertEquals("BUY", first.getType());
        assertEquals("BUY", second.getType());

        // Both at the same timestamp
        assertEquals(first.getTimestamp(), second.getTimestamp());
    }

    @Test
    @DisplayName("Staking Rewards and Simple Earn → STAKING_REWARD (not AIRDROP)")
    void testParseAirdrops() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_airdrops.csv"));

        assertEquals(2, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        assertEquals("STAKING_REWARD", result.getTransactions().get(0).getType());
        assertEquals("AXS",            result.getTransactions().get(0).getAsset());
        assertEquals("STAKING_REWARD", result.getTransactions().get(1).getType());
        assertEquals("BUSD",           result.getTransactions().get(1).getAsset());
    }

    @Test
    @DisplayName("Staking Redemption is skipped (no tax impact)")
    void testSkipStakingRedemption() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_transaction_history_full.csv"));
        assertEquals(6, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());
    }

    @Test
    @DisplayName("unitPriceEur is always null (no price data in Binance CSV)")
    void testNoPriceEnrichment() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_transaction_history_full.csv"));
        result.getTransactions().forEach(tx ->
                assertNull(tx.getUnitPriceEur(), "unitPriceEur should be null for " + tx));
    }

    @Test
    @DisplayName("externalId is always null (Binance does not provide order IDs in these exports)")
    void testNoExternalId() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_transaction_history_full.csv"));
        result.getTransactions().forEach(tx ->
                assertNull(tx.getExternalId(), "externalId should be null for " + tx));
    }

    @Test
    @DisplayName("Missing-price warnings: only emitted for rows that retained null unitPriceEur (EUR deposits, etc.)")
    void testMissingPriceWarnings() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_trade_buy.csv"));
        // Deposit has null price (EUR transfer), but the BUY from Transaction Related pair has calculated unitPriceEur
        assertTrue(result.getWarningCount() >= 1);
    }

    @Test
    @DisplayName("Timestamps are correctly parsed from yyyy-MM-dd HH:mm:ss UTC")
    void testTimestampParsing() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_trade_buy.csv"));
        Instant expected = ZonedDateTime.of(2021, 10, 24, 14, 17, 5, 0, ZoneId.of("UTC")).toInstant();
        // Deposit at same timestamp
        assertEquals(expected, result.getTransactions().get(0).getTimestamp());
    }

    @Test
    @DisplayName("Amount is always positive (absolute value of Change column)")
    void testAmountAlwaysPositive() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_transaction_history_full.csv"));
        result.getTransactions().forEach(tx ->
                assertTrue(tx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                        "Amount must be positive, was: " + tx.getAmount()));
    }

    @Test
    @DisplayName("sourceImporter is BinanceTransactionImporter")
    void testSourceImporterSet() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_airdrops.csv"));
        result.getTransactions().forEach(tx ->
                assertEquals("BinanceTransactionImporter", tx.getSourceImporter()));
    }

    @Test
    @DisplayName("rawLine is populated for every transaction")
    void testRawLinePopulated() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("binance_airdrops.csv"));
        result.getTransactions().forEach(tx -> {
            assertNotNull(tx.getRawLine());
            assertFalse(tx.getRawLine().isBlank());
        });
    }
}


