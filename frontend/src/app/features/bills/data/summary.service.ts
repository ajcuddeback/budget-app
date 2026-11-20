import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PeriodSummary } from './bills.models';

/**
 * What a household has left to spend in one month.
 *
 * **One request, not three.** The page leads with one figure, and a page that assembled it from
 * the bills, income and envelopes responses could show a headline its own blocks disagree with.
 * The subtraction happens on the server; this client only displays it.
 */
@Injectable({ providedIn: 'root' })
export class PeriodSummaryService {
  private readonly http = inject(HttpClient);

  /** @param period `YYYY-MM`. A malformed one is a `400` at the edge, never a query. */
  get(period: string): Observable<PeriodSummary> {
    return this.http.get<PeriodSummary>(`/api/periods/${encodeURIComponent(period)}/summary`);
  }
}
