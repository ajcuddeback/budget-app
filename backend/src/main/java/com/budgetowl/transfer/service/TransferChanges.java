package com.budgetowl.transfer.service;

import com.budgetowl.common.Change;
import java.time.LocalDate;

/**
 * A change to a transfer. Both legs are rewritten together or not at all.
 *
 * <p><b>The accounts are not here.</b> {@code Transaction} already fixes an account at creation,
 * because moving a row between accounts changes two balances and, where the currencies differ, the
 * meaning of the amount — and a transfer is two such rows, so it would change up to four balances.
 * Picking the wrong savings account is corrected by deleting the transfer and making it again,
 * which costs exactly what a mis-keyed transaction costs today.
 *
 * @param amountReceived required when the two accounts differ in currency, refused when they do not
 *     — the same rule as creating one
 * @param version the pair's version, not either leg's — see {@code TransferView.version}
 */
public record TransferChanges(
        LocalDate date, String amount, String amountReceived, Change<String> note, long version) {}
