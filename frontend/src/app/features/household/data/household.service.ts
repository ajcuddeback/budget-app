import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { HouseholdRole, Page } from '../../../core/auth.models';
import {
  CreateInvitationRequest,
  Household,
  Invitation,
  Member,
  OwnPreferences,
  UpdateHouseholdRequest,
} from './household.models';

const BASE = '/api/households/current';

/**
 * The one household an instance holds (ADR-0026). There is no id in any path: the server resolves
 * the household from the caller's verified membership, so there is nothing here to tamper with.
 * Which buttons the UI shows follows the role; what is *allowed* is decided again on the server.
 */
@Injectable({ providedIn: 'root' })
export class HouseholdService {
  private readonly http = inject(HttpClient);

  current(): Observable<Household> {
    return this.http.get<Household>(BASE);
  }

  update(request: UpdateHouseholdRequest): Observable<Household> {
    return this.http.put<Household>(BASE, request);
  }

  members(): Observable<Member[]> {
    return this.http.get<Page<Member>>(`${BASE}/members`, { params: { size: 200 } }).pipe(
      map((page) => page.content),
    );
  }

  changeRole(membershipId: string, role: HouseholdRole): Observable<Member> {
    return this.http.patch<Member>(`${BASE}/members/${encodeURIComponent(membershipId)}`, { role });
  }

  /** Removes a member, or — for your own membership id — leaves. Access only; data stays. */
  removeMember(membershipId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/members/${encodeURIComponent(membershipId)}`);
  }

  createInvitation(request: CreateInvitationRequest): Observable<Invitation> {
    return this.http.post<Invitation>(`${BASE}/invitations`, request);
  }

  revokeInvitation(invitationId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/invitations/${encodeURIComponent(invitationId)}`);
  }

  /** Full replacement: send both fields; `null` clears one back to its default. */
  updateOwnPreferences(preferences: OwnPreferences): Observable<Member> {
    return this.http.patch<Member>(`${BASE}/members/me`, preferences);
  }
}
