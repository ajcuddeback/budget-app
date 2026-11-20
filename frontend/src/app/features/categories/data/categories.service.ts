import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../../../core/auth.models';
import { Category, CreateCategoryRequest, UpdateCategoryRequest } from './category.models';

const BASE = '/api/categories';

/**
 * The server caps a household at this many categories, which is also the maximum page size — so
 * asking for it returns the whole tree in one request, every time.
 */
const PAGE_SIZE = 200;

/**
 * The household's categories.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017). An `id` in a path is a lookup key — another household's answers `404`.
 */
@Injectable({ providedIn: 'root' })
export class CategoriesService {
  private readonly http = inject(HttpClient);

  /**
   * One page — in practice all of them, see {@link PAGE_SIZE}.
   *
   * Archived rows are fetched with the rest and each carries `archived`, so the toggle that reveals
   * them is a client-side split rather than a second round trip that could disagree with the first.
   */
  list(includeArchived = true): Observable<Page<Category>> {
    return this.http.get<Page<Category>>(BASE, {
      params: { includeArchived, page: 0, size: PAGE_SIZE },
    });
  }

  get(id: string): Observable<Category> {
    return this.http.get<Category>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateCategoryRequest): Observable<Category> {
    return this.http.post<Category>(BASE, request);
  }

  /** Partial. `request.version` is what makes a concurrent edit a `409` instead of a silent loss. */
  update(id: string, request: UpdateCategoryRequest): Observable<Category> {
    return this.http.patch<Category>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  archive(id: string): Observable<Category> {
    return this.http.post<Category>(`${BASE}/${encodeURIComponent(id)}/archive`, null);
  }

  unarchive(id: string): Observable<Category> {
    return this.http.post<Category>(`${BASE}/${encodeURIComponent(id)}/unarchive`, null);
  }

  /**
   * Deletes the category and its children. **No transactions are deleted** — those filed under it
   * become "Needs a category", which the server does with a foreign key rather than a loop.
   */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }
}
