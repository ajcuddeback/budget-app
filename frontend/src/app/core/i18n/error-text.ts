import { Pipe, PipeTransform, inject } from '@angular/core';
import { ApiError } from '../api-error';
import { I18nService } from './i18n.service';

/**
 * Turns an {@link ApiError} into a sentence in the user's language.
 *
 * Resolution order: a code-and-reason key (`error.password-unacceptable.too-short`), then the code
 * (`error.rate-limited`), then a network or generic fallback. An unknown code — a newer server
 * than this client — gets the generic sentence, never the raw code.
 */
export function errorMessage(i18n: I18nService, error: ApiError): string {
  if (error.isNetwork) {
    return i18n.t('error.network');
  }
  const reason = typeof error.params['reason'] === 'string' ? error.params['reason'] : null;
  const candidates: string[] = [];
  if (error.code && reason) {
    candidates.push(`error.${error.code}.${reason}`);
  }
  if (error.code) {
    candidates.push(`error.${error.code}`);
  }
  const key = candidates.find((candidate) => i18n.has(candidate)) ?? 'error.generic';
  return i18n.t(key, {
    minimumLength: numberParam(error, 'minimumLength'),
    maximumBytes: numberParam(error, 'maximumBytes'),
    minutes: i18n.duration(error.retryAfterSeconds ?? numberParam(error, 'retryAfterSeconds')),
  });
}

function numberParam(error: ApiError, name: string): number {
  const value = error.params[name];
  return typeof value === 'number' ? value : 0;
}

@Pipe({ name: 'errorText', pure: false })
export class ErrorTextPipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(error: ApiError): string {
    return errorMessage(this.i18n, error);
  }
}
