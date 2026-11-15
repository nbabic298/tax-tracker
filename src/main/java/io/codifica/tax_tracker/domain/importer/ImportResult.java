package io.codifica.tax_tracker.domain.importer;

import java.util.*;

/**
 * Result of a transaction import.
 * Contains successfully parsed transactions, warnings, and unmatched legs.
 */
public class ImportResult {

    private final List<ParsedTransaction> transactions;
    private final List<String> warnings;
    private final List<String> unmatchedLegs; // CSV lines that look like orphan trade legs

    public ImportResult(
            List<ParsedTransaction> transactions,
            List<String> warnings,
            List<String> unmatchedLegs) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
        this.unmatchedLegs = Objects.requireNonNull(unmatchedLegs, "unmatchedLegs must not be null");
    }

    public List<ParsedTransaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getUnmatchedLegs() {
        return Collections.unmodifiableList(unmatchedLegs);
    }

    public int getTransactionCount() {
        return transactions.size();
    }

    public int getWarningCount() {
        return warnings.size();
    }

    public int getUnmatchedLegCount() {
        return unmatchedLegs.size();
    }

    @Override
    public String toString() {
        return "ImportResult{" +
                "transactions=" + transactions.size() +
                ", warnings=" + warnings.size() +
                ", unmatchedLegs=" + unmatchedLegs.size() +
                '}';
    }
}

