import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SolveTermsRequest, SolvedTerms } from './debt.models';

/**
 * Solving a term set without saving it — for the terms editor and the add form.
 *
 * **A pure calculation**: it reads no stored data, so it discloses nothing. It is still
 * authenticated and **rate-limited** (`429` with a finite `Retry-After`) because its cost depends
 * on the numbers in the request, which makes a loop of calls the thing to bound.
 *
 * `POST` despite reading nothing: the term set is a body with amounts in it, and amounts do not
 * belong in a URL where they reach access logs and browser history.
 */
@Injectable({ providedIn: 'root' })
export class DebtTermsService {
  private readonly http = inject(HttpClient);

  solve(request: SolveTermsRequest): Observable<SolvedTerms> {
    return this.http.post<SolvedTerms>('/api/debt-terms/solve', request);
  }
}
