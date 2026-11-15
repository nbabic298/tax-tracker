package io.codifica.tax_tracker.domain.importer;

/**
 * Thrown when transaction import fails due to format issues or other errors.
 */
public class ImportException extends Exception {
    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}

