import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
import { isApiError } from '../../../core/api-error';
import {
  CommitRequest,
  ImportBatch,
  ImportHistory,
  ImportMapping,
  ImportPreview,
  ImportRequest,
} from './import.models';

const BASE = '/api/imports';

/**
 * File import over HTTP.
 *
 * **The preview writes nothing; the commit sends the same file again** (ADR-0037). The file is in
 * memory for as long as this page holds it — never in `localStorage`, `sessionStorage`, IndexedDB
 * or a cache — and it goes as base64 inside JSON, never as `multipart/form-data`, which the server
 * does not accept (ADR-0038).
 *
 * No household id anywhere: the server resolves it from the caller's verified membership, and the
 * account id is a lookup key — another household's is a `404`.
 */
@Injectable({ providedIn: 'root' })
export class ImportsService {
  private readonly http = inject(HttpClient);

  preview(request: ImportRequest): Observable<ImportPreview> {
    return this.http.post<ImportPreview>(`${BASE}/preview`, request);
  }

  /** `201` with the batch; `200` with the same batch when a retry finds it already written. */
  commit(request: CommitRequest): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(BASE, request);
  }

  /** The household's imports, newest first. Counts only: no file name is kept to list. */
  list(page = 0, size = 10): Observable<ImportHistory> {
    return this.http.get<ImportHistory>(BASE, { params: { page, size } });
  }

  get(batchId: string): Observable<ImportBatch> {
    return this.http.get<ImportBatch>(`${BASE}/${encodeURIComponent(batchId)}`);
  }

  /** Undo: removes the batch's rows that are not transfer legs. */
  undo(batchId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(batchId)}`);
  }

  /** The account's saved CSV mapping, or `null` when it has none (`404`). */
  mapping(accountId: string): Observable<ImportMapping | null> {
    return this.http
      .get<ImportMapping>(`/api/accounts/${encodeURIComponent(accountId)}/import-mapping`)
      .pipe(
        catchError((failure: unknown) =>
          // The error interceptor has already turned the response into an ApiError.
          isApiError(failure) && failure.status === 404 ? of(null) : throwError(() => failure),
        ),
      );
  }
}
