import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { IncomeAndSpending, ReportName, SpendingByCategory } from './reports.models';

const BASE = '/api/reports';

/**
 * The two reports. **`GET` only**: there is nothing to write, and the server refuses every other
 * method on these paths.
 *
 * `from` and `to` are always sent: the server supplies no default range, because only the
 * member's own device knows what "the last twelve months" means where they are (decision 10).
 */
@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);

  incomeAndSpending(from: string, to: string): Observable<IncomeAndSpending> {
    return this.http.get<IncomeAndSpending>(`${BASE}/income-and-spending`, {
      params: { from, to },
    });
  }

  spendingByCategory(from: string, to: string): Observable<SpendingByCategory> {
    return this.http.get<SpendingByCategory>(`${BASE}/spending-by-category`, {
      params: { from, to },
    });
  }

  /**
   * The same report as a CSV file: the same resource, asked for with `Accept: text/csv`
   * (decision 8). Exactly the rows the page's table shows, because the server builds both from
   * one value.
   */
  csv(report: ReportName, from: string, to: string): Observable<Blob> {
    return this.http.get(`${BASE}/${report}`, {
      params: { from, to },
      headers: { Accept: 'text/csv' },
      responseType: 'blob',
    });
  }
}
