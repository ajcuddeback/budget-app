import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateIncomeRequest, IncomeList, IncomeSource, UpdateIncomeRequest } from './bills.models';

const BASE = '/api/income';

/**
 * The household's income sources.
 *
 * No household id in any path or body (ADR-0017), and **no occurrences anywhere**: nobody ticks
 * their salary off. A month's expected income is the schedule counted and multiplied, by the
 * server.
 */
@Injectable({ providedIn: 'root' })
export class IncomeService {
  private readonly http = inject(HttpClient);

  /** @param period `YYYY-MM`. Each source comes back with what it is worth that month. */
  list(period: string, includeInactive = true): Observable<IncomeList> {
    return this.http.get<IncomeList>(BASE, { params: { period, includeInactive } });
  }

  get(id: string, period: string): Observable<IncomeSource> {
    return this.http.get<IncomeSource>(`${BASE}/${encodeURIComponent(id)}`, { params: { period } });
  }

  create(request: CreateIncomeRequest): Observable<IncomeSource> {
    return this.http.post<IncomeSource>(BASE, request);
  }

  update(id: string, request: UpdateIncomeRequest): Observable<IncomeSource> {
    return this.http.patch<IncomeSource>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }
}
