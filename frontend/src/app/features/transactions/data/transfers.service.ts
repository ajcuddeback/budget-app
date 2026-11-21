import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateTransferRequest, Transfer, UpdateTransferRequest } from './transfer.models';

const BASE = '/api/transfers';

/**
 * The household's transfers.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017), and **both** account ids in a create are resolved against it — another
 * household's is a `404`, never a `403`.
 *
 * **Every id in a path here is a `transferGroupId`**, not a leg's transaction id. The ledger's own
 * endpoints refuse to edit or delete a leg (`409 transfer-leg-not-separate`), so these four are the
 * only way a transfer changes.
 *
 * Which buttons the UI offers follows the member's role; what is *allowed* is decided again on the
 * server, which refuses a `VIEWER` with `403 read-only-role` whatever the client did.
 */
@Injectable({ providedIn: 'root' })
export class TransfersService {
  private readonly http = inject(HttpClient);

  get(transferGroupId: string): Observable<Transfer> {
    return this.http.get<Transfer>(`${BASE}/${encodeURIComponent(transferGroupId)}`);
  }

  /** Creates both legs, in one database transaction. */
  create(request: CreateTransferRequest): Observable<Transfer> {
    return this.http.post<Transfer>(BASE, request);
  }

  /**
   * Rewrites both legs. `request.version` is the pair's, and a stale one is a `409` rather than a
   * silent loss of somebody else's correction.
   */
  update(transferGroupId: string, request: UpdateTransferRequest): Observable<Transfer> {
    return this.http.patch<Transfer>(`${BASE}/${encodeURIComponent(transferGroupId)}`, request);
  }

  /** Removes both legs. There is no way to remove one. */
  remove(transferGroupId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(transferGroupId)}`);
  }
}
