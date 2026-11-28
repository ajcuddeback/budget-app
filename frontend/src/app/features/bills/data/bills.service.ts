import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Bill,
  BillOccurrence,
  BillsPage,
  CreateBillRequest,
  RecordStatusRequest,
  UpdateBillRequest,
} from './bills.models';

const BASE = '/api/bills';

/**
 * The household's bills, and what it has recorded about them.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017). An id in a path is a lookup key — another household's answers `404`.
 *
 * **An occurrence is addressed by its due date, not by an id**, because for most of its life it
 * has no row (ADR-0032). `PUT` rather than `POST`: the URL is valid before anything exists at it,
 * and recording the same status twice is the same request twice.
 */
@Injectable({ providedIn: 'root' })
export class BillsService {
  private readonly http = inject(HttpClient);

  /**
   * One month's bills, each with the occurrences its schedule implies.
   *
   * @param period `YYYY-MM`. Required by the server: defaulting to "now" there would make the
   *   answer depend on the server's clock rather than on the person's.
   * @param includeInactive retired bills as well, so the screen can list them separately without
   *   a second round trip that could disagree with the first.
   */
  list(period: string, includeInactive = true): Observable<BillsPage> {
    return this.http.get<BillsPage>(BASE, { params: { period, includeInactive } });
  }

  get(id: string, period: string): Observable<Bill> {
    return this.http.get<Bill>(`${BASE}/${encodeURIComponent(id)}`, { params: { period } });
  }

  create(request: CreateBillRequest): Observable<Bill> {
    return this.http.post<Bill>(BASE, request);
  }

  update(id: string, request: UpdateBillRequest): Observable<Bill> {
    return this.http.patch<Bill>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  /** Deletes the bill and everything recorded about it. No transaction is deleted (ADR-0025). */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  /**
   * Records a status against one occurrence — the one-tap action on the page.
   *
   * @param dueDate `YYYY-MM-DD`, the occurrence's own date and not the month it falls in. Keying
   *   on the month would cap a weekly bill at one settled payment out of four or five.
   */
  record(id: string, dueDate: string, request: RecordStatusRequest): Observable<BillOccurrence> {
    return this.http.put<BillOccurrence>(
      `${BASE}/${encodeURIComponent(id)}/occurrences/${encodeURIComponent(dueDate)}`,
      request,
    );
  }

  /**
   * Forgets what was recorded, so the date reads as its projection again.
   *
   * **Not the same as recording `DUE`**: that is an assertion, and this is the absence of one.
   */
  forget(id: string, dueDate: string): Observable<void> {
    return this.http.delete<void>(
      `${BASE}/${encodeURIComponent(id)}/occurrences/${encodeURIComponent(dueDate)}`,
    );
  }
}
