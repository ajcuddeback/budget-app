import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Plan, UpdatePlanRequest } from './debt.models';

const BASE = '/api/debt-plan';

/**
 * The payoff plan — **singular**, because there is exactly one per household (ADR-0026).
 *
 * There is no plan id in any path and none in any response: a second row would be a second answer
 * to "what is my strategy".
 */
@Injectable({ providedIn: 'root' })
export class DebtPlanService {
  private readonly http = inject(HttpClient);

  /** The strategy, the amount, the order, **both** projections and the comparison. */
  get(): Observable<Plan> {
    return this.http.get<Plan>(BASE);
  }

  /**
   * Sets the strategy, the monthly amount and the custom order.
   *
   * A `VIEWER` is refused by the server: setting a strategy changes every projection the household
   * acts on, so it is a write even though it stores two values. The screen offers no control for
   * it either — refused *and* absent, not merely disabled-looking.
   */
  update(request: UpdatePlanRequest): Observable<Plan> {
    return this.http.put<Plan>(BASE, request);
  }

  /**
   * The what-if. **A `GET`, and it writes nothing** — not the amount it was asked about, not a
   * snapshot. *"Adding $50/mo moves the whole ladder up by 3 months"* is the plan re-run.
   *
   * @param strategy omitted to keep the selected one.
   * @param monthlyAmount a decimal **string** (ADR-0006), omitted to keep the plan's own.
   */
  projection(options: { strategy?: string; monthlyAmount?: string } = {}): Observable<Plan> {
    const params: Record<string, string> = {};
    if (options.strategy !== undefined) {
      params['strategy'] = options.strategy;
    }
    if (options.monthlyAmount !== undefined) {
      params['monthlyAmount'] = options.monthlyAmount;
    }
    return this.http.get<Plan>(`${BASE}/projection`, { params });
  }
}
