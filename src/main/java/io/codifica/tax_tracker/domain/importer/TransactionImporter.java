package io.codifica.tax_tracker.domain.importer;

import java.nio.file.Path;

/**
 * Pluggable interface for transaction importers.
 * Each importer is responsible for parsing a specific exchange/broker CSV format
 * and normalizing rows into a standard ParsedTransaction format.
 */
public interface TransactionImporter {

    /**
     * Determines if this importer supports the given CSV file.
     * Typically checks file name pattern or header row.
     *
     * @param csvPath path to the CSV file
     * @return true if this importer can handle the file
     */
    boolean supports(Path csvPath);

    /**
     * Parses a CSV file and normalizes rows into ParsedTransaction objects.
     * Does NOT perform price enrichment or persist to database.
     *
     * @param csvPath path to the CSV file
     * @return ImportResult containing parsed transactions, warnings, and unmatched legs
     * @throws ImportException if parsing fails
     */
    ImportResult importTransactions(Path csvPath) throws ImportException;
}

