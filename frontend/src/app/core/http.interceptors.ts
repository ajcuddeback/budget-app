import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { ApiError } from './api-error';
import { AuthService } from './auth.service';

const API_PREFIX = '/api/';

/** Only requests to this instance's own API are touched; a translation file is not one. */
function isApiRequest(url: string): boolean {
  return url.startsWith(API_PREFIX);
}

/**
 * Session-cookie auth (ADR-0004): every API call carries the cookie. Set once, here, rather than
 * at each call site, so a new service cannot forget it.
 *
 * Only relative `/api/` URLs qualify. Credentials are never attached to a request for another
 * origin, which also keeps Angular's XSRF interceptor — same-origin only — in step.
 */
export const credentialsInterceptor: HttpInterceptorFn = (request, next) =>
  isApiRequest(request.url) ? next(request.clone({ withCredentials: true })) : next(request);

/**
 * `401` from a request made while signed in means the session is gone: send the user to sign-in
 * with a reason. Requests that are *supposed* to be answered 401 when unauthenticated — the
 * profile probe, login, logout, the public setup and invitation endpoints — are exempt, or a wrong
 * password would redirect instead of explaining itself.
 *
 * Every failure becomes an {@link ApiError}, so no caller ever handles a raw `HttpErrorResponse`
 * (whose `error` body and URL can carry things nobody meant to keep).
 */
export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url)) {
    return next(request);
  }
  const auth = inject(AuthService);
  return next(request).pipe(
    catchError((failure: unknown) => {
      if (!(failure instanceof HttpErrorResponse)) {
        return throwError(() => failure);
      }
      const error = ApiError.from(failure);
      if (error.status === 401 && !isUnauthenticatedByDesign(request.url)) {
        void auth.sessionEnded();
      }
      return throwError(() => error);
    }),
  );
};

function isUnauthenticatedByDesign(url: string): boolean {
  return (
    url === '/api/auth/me' ||
    url === '/api/auth/login' ||
    url === '/api/auth/logout' ||
    url.startsWith('/api/setup/') ||
    url.startsWith('/api/invitations/')
  );
}
