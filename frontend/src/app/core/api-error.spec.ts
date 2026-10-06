import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ApiError, asApiError, isApiError } from './api-error';

function failure(status: number, error: unknown, headers?: HttpHeaders): HttpErrorResponse {
  return new HttpErrorResponse({ status, error, headers });
}

describe('ApiError', () => {
  it('reads the stable code, never the English title', () => {
    const error = ApiError.from(
      failure(401, { code: 'authentication-failed', title: 'Authentication failed', detail: 'x' }),
    );

    expect(error.code).toBe('authentication-failed');
    expect(error.status).toBe(401);
    expect(error.message).not.toContain('Authentication failed');
  });

  it('collects the fields a validation failure names, from either shape', () => {
    const fromParams = ApiError.from(
      failure(400, { code: 'validation-failed', params: { fields: ['displayName', 7, 'password'] } }),
    );
    const fromList = ApiError.from(
      failure(400, { code: 'validation-failed', errors: [{ field: 'email', message: 'x' }, 'junk'] }),
    );

    expect(fromParams.fields).toEqual(['displayName', 'password']);
    expect(fromList.fields).toEqual(['email']);
  });

  it('reads how long to wait from the params, then from the header', () => {
    const fromParams = ApiError.from(failure(429, { code: 'rate-limited', params: { retryAfterSeconds: 90 } }));
    const fromHeader = ApiError.from(
      failure(429, { code: 'rate-limited' }, new HttpHeaders({ 'Retry-After': '30' })),
    );
    const neither = ApiError.from(failure(429, { code: 'rate-limited' }));

    expect(fromParams.retryAfterSeconds).toBe(90);
    expect(fromHeader.retryAfterSeconds).toBe(30);
    expect(neither.retryAfterSeconds).toBeNull();
  });

  it('survives a body that is not a problem document', () => {
    for (const body of ['<html>', null, 42, ['a'], undefined]) {
      const error = ApiError.from(failure(502, body));
      expect(error.code).toBeNull();
      expect(error.params).toEqual({});
    }
  });

  it('treats status 0 as the server being unreachable', () => {
    expect(ApiError.from(failure(0, null)).isNetwork).toBe(true);
    expect(ApiError.from(failure(500, null)).isNetwork).toBe(false);
  });

  it('keeps the correlation id, the one thread back to the server log', () => {
    expect(ApiError.from(failure(500, { correlationId: 'abc' })).correlationId).toBe('abc');
  });

  it('narrows what was thrown', () => {
    const error = new ApiError(500, null);

    expect(isApiError(error)).toBe(true);
    expect(isApiError(new Error('x'))).toBe(false);
    expect(asApiError(error)).toBe(error);
    expect(asApiError('boom').isNetwork).toBe(true);
  });
});
