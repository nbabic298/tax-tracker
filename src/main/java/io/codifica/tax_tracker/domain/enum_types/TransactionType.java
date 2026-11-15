package io.codifica.tax_tracker.domain.enum_types;

/**
 * Transaction type classification as per CLAUDE.md requirements.
 *
 * Tax treatment distinctions:
 * - AIRDROP: gratuitous receipt, cost basis = 0 (Tax Administration opinion, 2021-09-06).
 * - STAKING_REWARD: yield income, cost basis = FMV at receipt date; taxed as income.
 *   These are treated differently from airdrops for Croatian capital gains purposes.
 */
public enum TransactionType {
    BUY,
    SELL,
    SWAP_IN,
    SWAP_OUT,
    AIRDROP,
    STAKING_REWARD,
    DIVIDEND,
    FEE,
    TRANSFER_IN,
    TRANSFER_OUT
}
