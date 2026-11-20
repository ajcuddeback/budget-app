import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../../../core/auth.models';
import { Account, CreateAccountRequest, UpdateAccountRequest } from './account.models';

const BASE = '/api/accounts';

/** The server clamps anything larger; asking for it keeps the one request honest. */
const PAGE_SIZE = 200;

/**
 * The household's accounts.
 *
 * There is no household id in any path and none in any body: the server resolves it from the
 * caller's verified membership (ADR-0017), so there is nothing here an attacker could swap. The
 * `id` in a path is a lookup key — another household's id answers `404`, not `403`.
 *
 * Which buttons the UI offers follows the member's role; what is *allowed* is decided again on the
 * server, which refuses a `VIEWER` with `403 read-only-role` whatever the client did.
 */
@Injectable({ providedIn: 'root' })
export class AccountsService {
  private readonly http = inject(HttpClient);

  /**
   * One page of accounts.
   *
   * Archived rows are fetched with the rest and each carries `archived`, which is what the feature
   * doc asks for: the toggle that reveals them is then a client-side split rather than a second
   * round trip that could disagree with the first.
   */
  list(includeArchived = true): Observable<Page<Account>> {
    return this.http.get<Page<Account>>(BASE, {
      params: { includeArchived, page: 0, size: PAGE_SIZE },
    });
  }

  get(id: string): Observable<Account> {
    return this.http.get<Account>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateAccountRequest): Observable<Account> {
    return this.http.post<Account>(BASE, request);
  }

  /** Partial. `request.version` is what makes a concurrent edit a `409` instead of a silent loss. */
  update(id: string, request: UpdateAccountRequest): Observable<Account> {
    return this.http.patch<Account>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  /** A sub-resource, not a field on `PATCH`: retiring an account is a different act. */
  archive(id: string): Observable<Account> {
    return this.http.post<Account>(`${BASE}/${encodeURIComponent(id)}/archive`, null);
  }

  unarchive(id: string): Observable<Account> {
    return this.http.post<Account>(`${BASE}/${encodeURIComponent(id)}/unarchive`, null);
  }

  /** Only while the account is empty; with transactions the server answers `account-not-empty`. */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }
}
