import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page } from '../../../core/auth.models';
import {
  CreateEntryRequest,
  CreateEnvelopeRequest,
  Envelope,
  EnvelopeEntry,
  EnvelopePage,
  UpdateEntryRequest,
  UpdateEnvelopeRequest,
} from './envelope.models';

const BASE = '/api/envelopes';

/** A period's envelopes fit in one page in any household anybody would want to use. */
const PAGE_SIZE = 200;

/**
 * The household's envelopes.
 *
 * No household id in any path or body: the server resolves it from the caller's verified
 * membership (ADR-0017).
 *
 * An entry's path names its envelope as well as the entry — an entry of the right household but
 * the wrong envelope is still the wrong entry for the request that asked for it, and the server
 * answers `404`.
 */
@Injectable({ providedIn: 'root' })
export class EnvelopesService {
  private readonly http = inject(HttpClient);

  /** @param period `YYYY-MM`. Required: an envelope without a month is not a thing. */
  list(period: string, includeArchived = true): Observable<EnvelopePage> {
    return this.http.get<EnvelopePage>(BASE, {
      params: { period, includeArchived, page: 0, size: PAGE_SIZE },
    });
  }

  /** The months that have envelopes, newest first — for the period picker. */
  periods(): Observable<string[]> {
    return this.http.get<string[]>(`${BASE}/periods`);
  }

  get(id: string): Observable<Envelope> {
    return this.http.get<Envelope>(`${BASE}/${encodeURIComponent(id)}`);
  }

  create(request: CreateEnvelopeRequest): Observable<Envelope> {
    return this.http.post<Envelope>(BASE, request);
  }

  update(id: string, request: UpdateEnvelopeRequest): Observable<Envelope> {
    return this.http.patch<Envelope>(`${BASE}/${encodeURIComponent(id)}`, request);
  }

  archive(id: string): Observable<Envelope> {
    return this.http.post<Envelope>(`${BASE}/${encodeURIComponent(id)}/archive`, null);
  }

  unarchive(id: string): Observable<Envelope> {
    return this.http.post<Envelope>(`${BASE}/${encodeURIComponent(id)}/unarchive`, null);
  }

  /** Deletes the envelope and its log. No transaction is deleted. */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  log(id: string, page = 0): Observable<Page<EnvelopeEntry>> {
    return this.http.get<Page<EnvelopeEntry>>(`${BASE}/${encodeURIComponent(id)}/entries`, {
      params: { page, size: 50 },
    });
  }

  /** Logs spend. An amount alone is enough — that is the whole point of the action. */
  logEntry(id: string, request: CreateEntryRequest): Observable<EnvelopeEntry> {
    return this.http.post<EnvelopeEntry>(`${BASE}/${encodeURIComponent(id)}/entries`, request);
  }

  updateEntry(
    id: string,
    entryId: string,
    request: UpdateEntryRequest,
  ): Observable<EnvelopeEntry> {
    return this.http.patch<EnvelopeEntry>(
      `${BASE}/${encodeURIComponent(id)}/entries/${encodeURIComponent(entryId)}`,
      request,
    );
  }

  removeEntry(id: string, entryId: string): Observable<void> {
    return this.http.delete<void>(
      `${BASE}/${encodeURIComponent(id)}/entries/${encodeURIComponent(entryId)}`,
    );
  }
}
