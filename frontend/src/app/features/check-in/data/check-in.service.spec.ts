import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CheckInService } from './check-in.service';

describe('CheckInService', () => {
  let http: HttpTestingController;
  let service: CheckInService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(CheckInService);
  });

  afterEach(() => {
    http.verify();
  });

  it('names no household anywhere and puts the week and item key in the path only', () => {
    service.current().subscribe();
    service.get('2026-W34').subscribe();
    service.history().subscribe();
    service.start('2026-W34').subscribe();
    service.discard('2026-W34').subscribe();
    service.answer('2026-W34', 'cat.t1', { choice: 'LEAVE' }).subscribe();
    service.clearAnswer('2026-W34', 'cat.t1').subscribe();
    service.complete('2026-W34', 6, 'a'.repeat(64)).subscribe();
    service.payeeRules().subscribe();
    service.removePayeeRule('r1').subscribe();
    service.chargeRules().subscribe();
    service.removeChargeRule('r2').subscribe();

    const seen: [string, string][] = [
      ['GET', '/api/check-ins/current'],
      ['GET', '/api/check-ins/2026-W34'],
      ['GET', '/api/check-ins'],
      ['PUT', '/api/check-ins/2026-W34'],
      ['DELETE', '/api/check-ins/2026-W34'],
      ['PUT', '/api/check-ins/2026-W34/answers/cat.t1'],
      ['DELETE', '/api/check-ins/2026-W34/answers/cat.t1'],
      ['POST', '/api/check-ins/2026-W34/complete'],
      ['GET', '/api/payee-rules'],
      ['DELETE', '/api/payee-rules/r1'],
      ['GET', '/api/charge-rules'],
      ['DELETE', '/api/charge-rules/r2'],
    ];
    for (const [method, url] of seen) {
      const request = http.expectOne(
        (candidate) => candidate.method === method && candidate.url === url,
      );
      expect(JSON.stringify(request.request.body ?? {})).not.toContain('household');
      if (url.endsWith('/complete')) {
        expect(request.request.body).toEqual({ version: 6, basis: 'a'.repeat(64) });
      }
      if (method === 'PUT' && url.endsWith('/answers/cat.t1')) {
        expect(request.request.body).toEqual({ choice: 'LEAVE' });
      }
      if (method === 'PUT' && url === '/api/check-ins/2026-W34') {
        expect(request.request.body).toBeNull();
      }
      request.flush(null);
    }
  });

  it('encodes a key so it can never become a second path segment', () => {
    service.answer('2026-W34', 'cat.x/../../admin', { choice: 'LEAVE' }).subscribe();

    const request = http.expectOne(() => true);
    expect(request.request.url).toBe('/api/check-ins/2026-W34/answers/cat.x%2F..%2F..%2Fadmin');
    request.flush(null);
  });
});
