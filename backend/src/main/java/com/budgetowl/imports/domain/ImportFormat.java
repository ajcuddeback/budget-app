package com.budgetowl.imports.domain;

/**
 * The statement formats accepted in v1 (docs/features/file-import.md, rule 2).
 *
 * <p>The request <b>declares</b> one and the server <b>verifies</b> it by sniffing the bytes. The
 * server never decides "this is probably CSV" on its own, and never tries several parsers in turn
 * on the same bytes. QFX is OFX with Intuit extensions and is declared as {@link #OFX}.
 */
public enum ImportFormat {
    CSV,
    OFX,
    QIF
}
