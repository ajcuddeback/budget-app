import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateTransactionRequest,
  Ledger,
  LedgerQuery,
  Transaction,
  UpdateTransactionRequest,
} from './transaction.models';

const BASE = '/api/transactions';

/** A ledger page. Large enough to feel like a list, small enough to stay quick. */
export const LEDGER_PAGE_SIZE = 50;

/**
 * The household's transactions.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017).
 */
@Injectable({ providedIn: 'root' })
export class TransactionsService {
  private readonly http = inject(HttpClient);

  /**
   * One page of the ledger, newest first.
   *
   * Filters are built as explicit query parameters and **only when set**: an empty string would be
   * a filter that matches nothing rather than no filter at all.
   */
  list(query: LedgerQuery = {}): Observable<Ledger> {
    let params = new HttpParams()
      .set('page', String(query.page ?? 0))
      .set('size', String(LEDGER_PAGE_SIZE));

    if (query.accountId) {
      params = params.set('accountId', query.accountId);
    }
    if (query.categoryId) {
      params = params.set('categoryId', query.categoryId);
    }
    if (query.uncategorised) {
      params = params.set('uncategorised', 'true');
    }
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    if (query.search && query.search.trim() !== '') {
      params = params.set('search', query.search.trim());
    }

    return this.http.get<Ledger>(BASE, { params });
  }

  get(id: string): Observable<Transaction> {
    return this.http.get<Transaction>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateTransactionRequest): Observable<Transaction> {
    return this.http.post<Transaction>(BASE, request);
  }

  /** Partial. `request.version` is what makes a concurrent edit a `409` instead of a silent loss. */
  update(id: string, request: UpdateTransactionRequest): Observable<Transaction> {
    return this.http.patch<Transaction>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  /**
   * Payee names starting with what has been typed, for the entry form.
   *
   * Names only — the server resolves the id when the transaction is saved, so the form never has to
   * hold one, and a new payee is simply a name it has not seen before.
   */
  suggestPayees(typed: string): Observable<string[]> {
    return this.http.get<string[]>(`${BASE}/payee-suggestions`, { params: { typed } });
  }
}
