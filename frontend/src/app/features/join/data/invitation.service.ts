import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AcceptedInvitation, AcceptInvitationRequest } from './join.models';

@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly http = inject(HttpClient);

  /**
   * Public, rate-limited, and single-use. The token is a credential: it is encoded into the path
   * here and nowhere else, and it is not kept, logged or put in an error.
   */
  accept(token: string, request: AcceptInvitationRequest): Observable<AcceptedInvitation> {
    return this.http.post<AcceptedInvitation>(
      `/api/invitations/${encodeURIComponent(token)}/accept`,
      request,
    );
  }
}
