import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiError } from '../../testing/helpers';
import { ErrorTextPipe, errorMessage } from './error-text';
import { I18nService } from './i18n.service';

describe('errorMessage', () => {
  let i18n: I18nService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    i18n = TestBed.inject(I18nService);
  });

  it('renders the sentence for a known code', () => {
    expect(errorMessage(i18n, apiError(401, 'authentication-failed'))).toBe(
      'The email address or password is not right.',
    );
  });

  it('prefers the more specific code-and-reason sentence, and fills its numbers', () => {
    const error = apiError(400, 'password-unacceptable', { reason: 'too-short', minimumLength: 12 });

    expect(errorMessage(i18n, error)).toBe('That password is too short. Use at least 12 characters.');
  });

  it('falls back to the plain code when the reason has no sentence of its own', () => {
    const error = apiError(400, 'password-unacceptable', { reason: 'surprising' });

    expect(errorMessage(i18n, error)).toBe('That password cannot be used. Choose a different one.');
  });

  it('says how long to wait, from the server\'s own figure', () => {
    expect(errorMessage(i18n, apiError(429, 'rate-limited', { retryAfterSeconds: 300 }))).toBe(
      'Too many attempts. Wait 5 minutes and try again.',
    );
  });

  it('never shows an unknown code, however it arrives', () => {
    const message = errorMessage(i18n, apiError(418, 'code-from-the-future'));

    expect(message).toBe('Something went wrong on our side. Try again in a moment.');
    expect(message).not.toContain('future');
  });

  it('says the server could not be reached for status 0', () => {
    expect(errorMessage(i18n, apiError(0))).toContain('could not be reached');
  });

  it('is available as a pipe', () => {
    const pipe = TestBed.runInInjectionContext(() => new ErrorTextPipe());

    expect(pipe.transform(apiError(403, 'owner-only'))).toBe('Only an owner of the household can do that.');
  });
});
