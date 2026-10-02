import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { Page } from '../../../core/auth.models';
import { Device } from './device.models';

@Injectable({ providedIn: 'root' })
export class DevicesService {
  private readonly http = inject(HttpClient);

  list(): Observable<Device[]> {
    return this.http
      .get<Page<Device>>('/api/auth/devices', { params: { size: 200 } })
      .pipe(map((page) => page.content));
  }

  /** Immediate: the credential stops working on that device's very next request. */
  revoke(deviceId: string): Observable<void> {
    return this.http.delete<void>(`/api/auth/devices/${encodeURIComponent(deviceId)}`);
  }
}
