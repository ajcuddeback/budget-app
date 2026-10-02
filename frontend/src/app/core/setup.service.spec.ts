import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SetupService } from './setup.service';

describe('SetupService', () => {
  it('asks the instance whether it is fresh, and posts the first user to the public endpoint', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(SetupService);
    const http = TestBed.inject(HttpTestingController);

    service.status().subscribe();
    expect(http.expectOne('/api/setup/status').request.method).toBe('GET');
    service
      .createFirstUser({ email: 'a@example.test', displayName: 'A', password: 'pw', householdName: 'H', baseCurrency: 'USD' })
      .subscribe();
    const post = http.expectOne('/api/setup/first-user');

    expect(post.request.method).toBe('POST');
    expect(post.request.body.householdName).toBe('H');
    http.verify();
  });
});
