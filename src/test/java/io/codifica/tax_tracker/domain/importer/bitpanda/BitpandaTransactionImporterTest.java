package io.codifica.tax_tracker.domain.importer.bitpanda;

import io.codifica.tax_tracker.domain.importer.ImportException;
import io.codifica.tax_tracker.domain.importer.ImportResult;
import io.codifica.tax_tracker.domain.importer.ParsedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BitpandaTransactionImporter")
class BitpandaTransactionImporterTest {

    private BitpandaTransactionImporter importer;
    private Path resources;

    @BeforeEach
    void setUp() {
        importer  = new BitpandaTransactionImporter();
        resources = Paths.get("src/test/resources");
    }

    // -------------------------------------------------------------------------
    // supports()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("supports() matches Bitpanda file name pattern")
    void testSupports() {
        assertTrue(importer.supports(Paths.get("bitpanda-trades-2024-01-01.csv")));
        assertTrue(importer.supports(Paths.get("BITPANDA_EXPORT.csv")));
        assertFalse(importer.supports(Paths.get("binance-transactions.csv")));
        assertFalse(importer.supports(Paths.get("kraken.csv")));
    }

    // -------------------------------------------------------------------------
    // Simple buy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Simple buy: emits one BUY with price, fiat amount, externalId")
    void testSimpleBuy() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_buy.csv"));

        assertEquals(1, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        ParsedTransaction tx = result.getTransactions().get(0);
        assertEquals("BUY",                              tx.getType());
        assertEquals("ADA",                              tx.getAsset());
        assertEquals(0, new BigDecimal("265.07057806").compareTo(tx.getAmount()));
        assertEquals(0, new BigDecimal("1.89").compareTo(tx.getUnitPriceEur()));
        assertEquals(0, new BigDecimal("500.00").compareTo(tx.getFiatAmount()));
        assertEquals("Tc4d42caf-2efd-443d-8d08-011524c416a0", tx.getExternalId());
        assertEquals(BitpandaTransactionImporter.IMPORTER_NAME, tx.getSourceImporter());
    }

    // -------------------------------------------------------------------------
    // Simple sell
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Simple sell: emits one SELL with price and externalId")
    void testSimpleSell() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_sell.csv"));

        assertEquals(1, result.getTransactionCount());

        ParsedTransaction tx = result.getTransactions().get(0);
        assertEquals("SELL", tx.getType());
        assertEquals("SRM",  tx.getAsset());
        assertEquals(0, new BigDecimal("206.41411860").compareTo(tx.getAmount()));
        assertEquals(0, new BigDecimal("0.04").compareTo(tx.getUnitPriceEur()));
        assertNotNull(tx.getExternalId());
    }

    // -------------------------------------------------------------------------
    // Swap (crypto-to-crypto, same ID prefix)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Swap: two rows with same ID prefix emitted as SELL + BUY")
    void testSwap() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_swap.csv"));

        assertEquals(2, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());
        assertEquals(0, result.getWarningCount());   // prices present — no warnings

        ParsedTransaction sell = result.getTransactions().get(0);
        ParsedTransaction buy  = result.getTransactions().get(1);

        assertEquals("SELL", sell.getType());
        assertEquals("SRM",  sell.getAsset());
        assertEquals(0, new BigDecimal("206.41411860").compareTo(sell.getAmount()));

        assertEquals("BUY",  buy.getType());
        assertEquals("BTC",  buy.getAsset());
        assertEquals(0, new BigDecimal("0.00028846").compareTo(buy.getAmount()));
        assertEquals(0, new BigDecimal("26554.81").compareTo(buy.getUnitPriceEur()));

        // Both timestamps must match
        assertEquals(sell.getTimestamp(), buy.getTimestamp());
    }

    // -------------------------------------------------------------------------
    // Reward (airdrop)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Reward: emitted as STAKING_REWARD (not AIRDROP) with price populated")
    void testReward() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_reward.csv"));

        assertEquals(1, result.getTransactionCount());

        ParsedTransaction tx = result.getTransactions().get(0);
        assertEquals("STAKING_REWARD", tx.getType());
        assertEquals("ETH",            tx.getAsset());
        assertEquals(0, new BigDecimal("0.00005434").compareTo(tx.getAmount()));
        assertEquals(0, new BigDecimal("2760.40").compareTo(tx.getUnitPriceEur()));
    }

    // -------------------------------------------------------------------------
    // Mixed fixture
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mixed: fiat deposit skipped, crypto deposit→TRANSFER_IN, withdrawal→TRANSFER_OUT, " +
                 "fiat transfer skipped, crypto transfer→TRANSFER_IN, stake/unstake skipped, ETF buy with spread")
    void testMixed() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_mixed.csv"));

        // Expected: fiat deposit skipped, ETH deposit, ADA withdrawal, fiat transfer skipped,
        //           BEST transfer_in, ADA unstake skipped, ETH stake skipped, S&P500 buy
        // = 4 transactions
        assertEquals(4, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        var txs = result.getTransactions();

        // ETH deposit → TRANSFER_IN
        ParsedTransaction ethDeposit = txs.get(0);
        assertEquals("TRANSFER_IN", ethDeposit.getType());
        assertEquals("ETH",         ethDeposit.getAsset());

        // ADA withdrawal → TRANSFER_OUT with fee
        ParsedTransaction adaWithdraw = txs.get(1);
        assertEquals("TRANSFER_OUT", adaWithdraw.getType());
        assertEquals("ADA",          adaWithdraw.getAsset());
        assertEquals(0, new BigDecimal("0.16417000").compareTo(adaWithdraw.getFeeAmount()));
        assertEquals("ADA", adaWithdraw.getFeeAsset());

        // BEST incoming transfer → TRANSFER_IN
        ParsedTransaction bestTransfer = txs.get(2);
        assertEquals("TRANSFER_IN", bestTransfer.getType());
        assertEquals("BEST",        bestTransfer.getAsset());

        // S&P500 buy (ETF derivative) with spread
        ParsedTransaction etfBuy = txs.get(3);
        assertEquals("BUY",    etfBuy.getType());
        assertEquals("S&P500", etfBuy.getAsset());
        assertEquals(0, new BigDecimal("5.01000000").compareTo(etfBuy.getSpread()));
        assertEquals(0, new BigDecimal("46.84").compareTo(etfBuy.getUnitPriceEur()));
    }

    // -------------------------------------------------------------------------
    // BCPEUR pass-through
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("BCPEUR sell + real asset buy: BCPEUR leg skipped, real asset BUY emitted")
    void testBcpeurPassThrough() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_bcpeur_buy.csv"));

        // Only the S&P500 BUY should come through; BCPEUR sell is skipped
        assertEquals(1, result.getTransactionCount());

        ParsedTransaction tx = result.getTransactions().get(0);
        assertEquals("BUY",    tx.getType());
        assertEquals("S&P500", tx.getAsset());
        assertEquals(0, new BigDecimal("22.09000000").compareTo(tx.getSpread()));
    }

    // -------------------------------------------------------------------------
    // Multi-swap (same ID prefix, two sell+buy pairs)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Multi-swap: same ID prefix with 2×(sell+buy) produces 4 transactions")
    void testMultiSwap() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_multi_swap.csv"));

        assertEquals(4, result.getTransactionCount());
        assertEquals(0, result.getUnmatchedLegCount());

        var txs = result.getTransactions();
        assertEquals("SELL", txs.get(0).getType()); assertEquals("GODL", txs.get(0).getAsset());
        assertEquals("BUY",  txs.get(1).getType()); assertEquals("BEST", txs.get(1).getAsset());
        assertEquals("SELL", txs.get(2).getType()); assertEquals("BST",  txs.get(2).getAsset());
        assertEquals("BUY",  txs.get(3).getType()); assertEquals("BEST", txs.get(3).getAsset());
    }

    // -------------------------------------------------------------------------
    // Timestamps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Timestamps are parsed correctly from ISO-8601 with timezone offset")
    void testTimestampParsing() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_buy.csv"));

        // 2021-09-20T16:39:31+02:00 → UTC = 14:39:31
        var expected = OffsetDateTime.parse("2021-09-20T16:39:31+02:00").toInstant();
        assertEquals(expected, result.getTransactions().get(0).getTimestamp());
    }

    // -------------------------------------------------------------------------
    // Amount is always positive
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Amount is always positive")
    void testAmountPositive() throws ImportException {
        ImportResult result = importer.importTransactions(resources.resolve("bitpanda_mixed.csv"));
        for (ParsedTransaction tx : result.getTransactions()) {
            assertTrue(tx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                    "Amount must be positive, was: " + tx.getAmount() + " for " + tx);
        }
    }

    // -------------------------------------------------------------------------
    // idPrefix helper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("idPrefix extracts text before first hyphen")
    void testIdPrefix() {
        assertEquals("T1ee62912", BitpandaTransactionImporter.idPrefix("T1ee62912-46c4-6b47-af7c-5fda3ce39623"));
        assertEquals("F6f665669", BitpandaTransactionImporter.idPrefix("F6f665669-7e89-4694-9c3a-626d2499abdc"));
        assertEquals("1efbcb66",  BitpandaTransactionImporter.idPrefix("1efbcb66-8f0d-6c60-e1bb-f1a407d78d32"));
        assertEquals("nohyphen",  BitpandaTransactionImporter.idPrefix("nohyphen"));
    }
}


