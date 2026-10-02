import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FirstUserRequest, FirstUserResponse, SetupStatus } from './setup.models';

@Injectable({ providedIn: 'root' })
export class SetupService {
  private readonly http = inject(HttpClient);

  status(): Observable<SetupStatus> {
    return this.http.get<SetupStatus>('/api/setup/status');
  }

  /** Public, and refused by the server the moment any user exists. */
  createFirstUser(request: FirstUserRequest): Observable<FirstUserResponse> {
    return this.http.post<FirstUserResponse>('/api/setup/first-user', request);
  }
}
