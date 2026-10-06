import { HttpErrorResponse } from '@angular/common/http';

/**
 * What the API said went wrong, as data — never as a sentence.
 *
 * The API answers with RFC 7807 `application/problem+json` carrying a stable `code` (ADR-0023);
 * the client renders the sentence in the user's language. `title` and `detail` in the body are
 * developer-facing English and are deliberately not read here.
 *
 * Nothing the user typed is ever kept on this object, and its `message` is only the status and
 * code: an error that is logged or thrown to the console must not be able to carry a password.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly params: Readonly<Record<string, unknown>> = {},
    /** Names of fields the server rejected, from `params.fields` or the `errors` list. */
    readonly fields: readonly string[] = [],
    readonly correlationId: string | null = null,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(`API error ${status}${code ? ` ${code}` : ''}`);
    this.name = 'ApiError';
  }

  /** The request never got an answer: server down, offline, blocked, or timed out. */
  get isNetwork(): boolean {
    return this.status === 0;
  }

  static from(response: HttpErrorResponse): ApiError {
    const body: unknown = response.error;
    const problem = isRecord(body) ? body : {};
    const params = isRecord(problem['params']) ? problem['params'] : {};
    const code = typeof problem['code'] === 'string' ? problem['code'] : null;
    return new ApiError(
      response.status,
      code,
      params,
      fieldNames(problem, params),
      typeof problem['correlationId'] === 'string' ? problem['correlationId'] : null,
      retryAfter(response, params),
    );
  }
}

export function isApiError(candidate: unknown): candidate is ApiError {
  return candidate instanceof ApiError;
}

/** Narrows anything thrown to an {@link ApiError}, wrapping what is not one. */
export function asApiError(thrown: unknown): ApiError {
  return isApiError(thrown) ? thrown : new ApiError(0, null);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function fieldNames(problem: Record<string, unknown>, params: Record<string, unknown>): string[] {
  const names: string[] = [];
  const listed = params['fields'];
  if (Array.isArray(listed)) {
    names.push(...listed.filter((name): name is string => typeof name === 'string'));
  }
  const errors = problem['errors'];
  if (Array.isArray(errors)) {
    for (const entry of errors) {
      if (isRecord(entry) && typeof entry['field'] === 'string') {
        names.push(entry['field']);
      }
    }
  }
  return names;
}

function retryAfter(response: HttpErrorResponse, params: Record<string, unknown>): number | null {
  const fromParams = params['retryAfterSeconds'];
  if (typeof fromParams === 'number' && Number.isFinite(fromParams)) {
    return fromParams;
  }
  const header = Number(response.headers?.get('Retry-After'));
  return Number.isFinite(header) && header > 0 ? header : null;
}
