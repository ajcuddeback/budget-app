import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Contribution,
  CreateContributionRequest,
  CreateGoalRequest,
  DraftPreviewRequest,
  Goal,
  GoalPage,
  GoalPreviewRequest,
  Page,
  PlanStep,
  SetPlanRequest,
  UpdateGoalRequest,
} from './goal.models';

const BASE = '/api/goals';

/**
 * The household's goals, their plans and their contributions.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017). An id in a path is a lookup key — another household's answers `404`.
 *
 * **Nothing here computes a figure.** `saved`, `remaining`, the projection and the required
 * monthly amount all come from the server, previews included; the browser states them.
 *
 * `withCredentials` is set once in `core/http.interceptors.ts` (ADR-0004), never here.
 */
@Injectable({ providedIn: 'root' })
export class GoalsService {
  private readonly http = inject(HttpClient);

  /** Every goal with its figures, archived ones included so the page can show them apart. */
  list(includeArchived = true, page = 0, size = 50): Observable<GoalPage> {
    return this.http.get<GoalPage>(BASE, { params: { includeArchived, page, size } });
  }

  get(id: string): Observable<Goal> {
    return this.http.get<Goal>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateGoalRequest): Observable<Goal> {
    return this.http.post<Goal>(BASE, request);
  }

  /** Sends exactly what `goal-patch.ts` built: an absent key and a `null` are different. */
  update(id: string, request: UpdateGoalRequest): Observable<Goal> {
    return this.http.patch<Goal>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  /** Takes its plan and contributions and no transaction. */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  archive(id: string): Observable<Goal> {
    return this.http.post<Goal>(`${BASE}/${encodeURIComponent(id)}/archive`, {});
  }

  unarchive(id: string): Observable<Goal> {
    return this.http.post<Goal>(`${BASE}/${encodeURIComponent(id)}/unarchive`, {});
  }

  plan(id: string, page = 0, size = 50): Observable<Page<PlanStep>> {
    return this.http.get<Page<PlanStep>>(`${BASE}/${encodeURIComponent(id)}/contribution-plan`, {
      params: { page, size },
    });
  }

  /** From `fromPeriod` on, the plan is this amount. Never a past month: the server refuses it. */
  setPlan(id: string, fromPeriod: string, request: SetPlanRequest): Observable<Goal> {
    return this.http.put<Goal>(
      `${BASE}/${encodeURIComponent(id)}/contribution-plan/${encodeURIComponent(fromPeriod)}`,
      request,
    );
  }

  /** Newest first, in the server's order. Paginated. */
  contributions(id: string, page = 0, size = 50): Observable<Page<Contribution>> {
    return this.http.get<Page<Contribution>>(`${BASE}/${encodeURIComponent(id)}/contributions`, {
      params: { page, size },
    });
  }

  /** Writes no transaction and moves no account balance (ADR-0035). */
  contribute(id: string, request: CreateContributionRequest): Observable<Contribution> {
    return this.http.post<Contribution>(`${BASE}/${encodeURIComponent(id)}/contributions`, request);
  }

  removeContribution(id: string, contributionId: string): Observable<void> {
    return this.http.delete<void>(
      `${BASE}/${encodeURIComponent(id)}/contributions/${encodeURIComponent(contributionId)}`,
    );
  }

  /**
   * What this goal would report with the body substituted, **without saving anything**.
   *
   * A `POST` although it writes nothing: an amount in a URL is in every access log. It needs write
   * access for the same reason — a `VIEWER` never sees the dialogs it serves.
   */
  preview(id: string, request: GoalPreviewRequest): Observable<unknown> {
    return this.http.post<unknown>(`${BASE}/${encodeURIComponent(id)}/projection-preview`, request);
  }

  /** The add form's preview, before a goal exists. Pure arithmetic over the body, on the server. */
  previewDraft(request: DraftPreviewRequest): Observable<unknown> {
    return this.http.post<unknown>(`${BASE}/projection-preview`, request);
  }
}
