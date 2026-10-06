import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InvitationService } from './invitation.service';

describe('InvitationService', () => {
  it('puts the token in the path, encoded, and nothing else about it anywhere', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);

    TestBed.inject(InvitationService).accept('a/b c', { displayName: 'R', password: 'pw' }).subscribe();
    const request = http.expectOne('/api/invitations/a%2Fb%20c/accept');

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ displayName: 'R', password: 'pw' });
    expect(JSON.stringify(request.request.body)).not.toContain('a/b c');
    http.verify();
  });
});
