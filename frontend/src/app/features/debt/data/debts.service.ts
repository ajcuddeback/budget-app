import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ConfirmBalanceRequest,
  CorrectPaymentRequest,
  CreateDebtRequest,
  DebtDetail,
  DebtList,
  DebtPayment,
  DebtPaymentPage,
  LogPaymentRequest,
  UpdateDebtRequest,
} from './debt.models';

const BASE = '/api/debts';

/**
 * The household's debts, their terms and their payments.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017). An id in a path is a lookup key — another household's answers `404`, so
 * there is no oracle to build.
 *
 * `withCredentials` is set once in `core/http.interceptors.ts` (ADR-0004), never here.
 */
@Injectable({ providedIn: 'root' })
export class DebtsService {
  private readonly http = inject(HttpClient);

  /**
   * Every debt, with its derived balance, its rung, what it is paying and when it clears.
   *
   * @param includeArchived an archived debt leaves the list and the ladder; each row carries
   *   `archived` so a caller that asks for them can tell which is which.
   */
  list(includeArchived = false): Observable<DebtList> {
    return this.http.get<DebtList>(BASE, { params: { includeArchived } });
  }

  get(id: string): Observable<DebtDetail> {
    return this.http.get<DebtDetail>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateDebtRequest): Observable<DebtDetail> {
    return this.http.post<DebtDetail>(BASE, request);
  }

  /**
   * The terms editor.
   *
   * The body carries the entered set **explicitly** — see {@link UpdateDebtRequest}. This method
   * sends what it is given and builds nothing, so the three-way decision is made once, in
   * `debt-patch.ts`, where it can be tested.
   */
  update(id: string, request: UpdateDebtRequest): Observable<DebtDetail> {
    return this.http.patch<DebtDetail>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  /** Takes the debt and its own payment history. No transactions, ever (ADR-0035). */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  /** Retiring a debt is a different act from editing it, so it is its own sub-resource. */
  archive(id: string): Observable<DebtDetail> {
    return this.http.post<DebtDetail>(`${BASE}/${encodeURIComponent(id)}/archive`, {});
  }

  unarchive(id: string): Observable<DebtDetail> {
    return this.http.post<DebtDetail>(`${BASE}/${encodeURIComponent(id)}/unarchive`, {});
  }

  /**
   * Confirms or corrects the balance from a statement.
   *
   * Writes the anchor, and a `STATEMENT_CORRECTION` row where the figure disagrees with the
   * arithmetic. History is never silently adjusted to agree with the present.
   */
  confirmBalance(id: string, request: ConfirmBalanceRequest): Observable<DebtDetail> {
    return this.http.post<DebtDetail>(`${BASE}/${encodeURIComponent(id)}/balance`, request);
  }

  /** The history, **paginated** — a household with years of records has more than one response. */
  payments(id: string, page = 0, size = 50): Observable<DebtPaymentPage> {
    return this.http.get<DebtPaymentPage>(`${BASE}/${encodeURIComponent(id)}/payments`, {
      params: { page, size },
    });
  }

  /** Logs a payment. **Writes no transaction and moves no account balance** (ADR-0035). */
  logPayment(id: string, request: LogPaymentRequest): Observable<DebtPayment> {
    return this.http.post<DebtPayment>(`${BASE}/${encodeURIComponent(id)}/payments`, request);
  }

  /** Corrects a payment. The interest that was true on the day is kept by the server. */
  correctPayment(
    id: string,
    paymentId: string,
    request: CorrectPaymentRequest,
  ): Observable<DebtPayment> {
    return this.http.patch<DebtPayment>(
      `${BASE}/${encodeURIComponent(id)}/payments/${encodeURIComponent(paymentId)}`,
      request,
    );
  }

  /** Removes a payment. The balance moves back; nothing in the ledger is touched. */
  removePayment(id: string, paymentId: string): Observable<void> {
    return this.http.delete<void>(
      `${BASE}/${encodeURIComponent(id)}/payments/${encodeURIComponent(paymentId)}`,
    );
  }
}
