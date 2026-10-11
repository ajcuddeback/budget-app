package com.budgetowl.account.service;

import java.util.List;

/**
 * One page of accounts, paged <b>in the query</b> rather than in memory.
 *
 * <p>A household's members are bounded by how many people live in a house, so {@code
 * PageResponse.of} may materialise them and slice the list. A financial collection may not: the
 * number of accounts is small today and the same code shape will be asked for transactions
 * tomorrow, where materialising everything is how a self-hoster's box runs out of heap.
 */
public record AccountPage(
        List<AccountView> accounts, int page, int size, long totalElements, int totalPages) {

    public AccountPage {
        accounts = List.copyOf(accounts);
    }
}
