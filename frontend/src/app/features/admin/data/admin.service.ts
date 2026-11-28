import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AdminAccountList,
  InstanceInfo,
  InstanceSettings,
  UpdateSettingsRequest,
} from './admin.models';

const BASE = '/api/admin';

/**
 * The four administrator-only routes (`AdminController`).
 *
 * Nothing is scoped by a household here: this is the instance, not its money. The caller is the
 * session, and the server refuses everybody but the one administrator (ADR-0029) — hiding the
 * screen is a courtesy, the `403 not-instance-admin` is the control.
 *
 * **The client secret goes out in a body and never comes back.** Never in a URL, a query parameter
 * or a `GET`: those land in browser history, a proxy log and a referrer header.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  instance(): Observable<InstanceInfo> {
    return this.http.get<InstanceInfo>(`${BASE}/instance`);
  }

  settings(): Observable<InstanceSettings> {
    return this.http.get<InstanceSettings>(`${BASE}/settings`);
  }

  /** A partial change. `version` is what the caller read; a stale one answers `409`. */
  updateSettings(request: UpdateSettingsRequest): Observable<InstanceSettings> {
    return this.http.patch<InstanceSettings>(`${BASE}/settings`, request);
  }

  accounts(): Observable<AdminAccountList> {
    return this.http.get<AdminAccountList>(`${BASE}/users`);
  }
}
