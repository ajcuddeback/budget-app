import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../../../core/auth.models';
import { AnswerBody, ChargeRule, HistoryEntry, PayeeRule, Week } from './check-in.models';

const BASE = '/api/check-ins';

/** `2026-W34`, and nothing else reaches a path. The server parses it strictly as well. */
export const WEEK_PATTERN = /^\d{4}-W\d{2}$/;

/**
 * The household's weekly check-in, and the rules completing one creates.
 *
 * - **No household id in any path or body**: the server resolves it from the caller's verified
 *   membership (ADR-0017). A week or an item key in a path is a lookup key, and one that is not
 *   this household's answers `404`.
 * - **The draft lives on the server and nowhere in the browser.** Every answer is one request, and
 *   nothing here keeps a copy in `localStorage` or `sessionStorage`. Resuming on another device is
 *   reading the week again.
 * - **Nothing here computes a figure.** Each write answers with the whole week, so the page shows
 *   the server's numbers after every answer.
 *
 * `withCredentials` and the CSRF header are set once in `core/http.interceptors.ts` (ADR-0004).
 */
@Injectable({ providedIn: 'root' })
export class CheckInService {
  private readonly http = inject(HttpClient);

  /** The week containing the server's today: what the ledger prompt and `/check-in` open. */
  current(): Observable<Week> {
    return this.http.get<Week>(`${BASE}/current`);
  }

  get(week: string): Observable<Week> {
    return this.http.get<Week>(`${BASE}/${encodeURIComponent(week)}`);
  }

  /** Newest week first. */
  history(page = 0, size = 20): Observable<Page<HistoryEntry>> {
    return this.http.get<Page<HistoryEntry>>(BASE, { params: { page, size } });
  }

  /** Idempotent: two members pressing Start get one draft. No body. */
  start(week: string): Observable<Week> {
    return this.http.put<Week>(`${BASE}/${encodeURIComponent(week)}`, null);
  }

  /** Writes nothing outside the check-in. */
  discard(week: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(week)}`);
  }

  /** Records or replaces one answer, and answers with the whole week as it now reads. */
  answer(week: string, itemKey: string, body: AnswerBody): Observable<Week> {
    return this.http.put<Week>(
      `${BASE}/${encodeURIComponent(week)}/answers/${encodeURIComponent(itemKey)}`,
      body,
    );
  }

  clearAnswer(week: string, itemKey: string): Observable<void> {
    return this.http.delete<void>(
      `${BASE}/${encodeURIComponent(week)}/answers/${encodeURIComponent(itemKey)}`,
    );
  }

  /**
   * Everything, atomically. `version` proves the person saw every answer, `basis` that the week has
   * not moved underneath them. A retry after a lost response is `409 check-in-completed`.
   */
  complete(week: string, version: number, basis: string): Observable<Week> {
    return this.http.post<Week>(`${BASE}/${encodeURIComponent(week)}/complete`, {
      version,
      basis,
    });
  }

  payeeRules(page = 0, size = 200): Observable<Page<PayeeRule>> {
    return this.http.get<Page<PayeeRule>>('/api/payee-rules', { params: { page, size } });
  }

  removePayeeRule(id: string): Observable<void> {
    return this.http.delete<void>(`/api/payee-rules/${encodeURIComponent(id)}`);
  }

  chargeRules(page = 0, size = 200): Observable<Page<ChargeRule>> {
    return this.http.get<Page<ChargeRule>>('/api/charge-rules', { params: { page, size } });
  }

  removeChargeRule(id: string): Observable<void> {
    return this.http.delete<void>(`/api/charge-rules/${encodeURIComponent(id)}`);
  }
}
