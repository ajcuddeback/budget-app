package com.budgetowl.imports.domain;

/**
 * What one commit did with the rows of a file.
 *
 * @param imported written as new transactions
 * @param duplicate already in the household (tier 1), never written
 * @param excluded suspected duplicates the person chose not to import
 * @param unreadable rows the file could not be read for
 * @param zero rows of zero, which move no money and are never imported
 */
public record ImportCounts(int imported, int duplicate, int excluded, int unreadable, int zero) {

    public ImportCounts {
        if (imported < 0 || duplicate < 0 || excluded < 0 || unreadable < 0 || zero < 0) {
            throw new IllegalArgumentException("a count cannot be negative");
        }
    }
}
