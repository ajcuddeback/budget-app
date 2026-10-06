import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18nService } from './i18n.service';
import { MessageKey, MessageParams } from './messages';

/**
 * `{{ 'login.title' | t }}` — translate a key, optionally with `{name}` parameters.
 *
 * Impure on purpose: the result depends on the active language, which is a signal read inside
 * `transform`, so it must be re-evaluated when the language changes. The lookup is a map read.
 *
 * Output is always interpolated as text, never as HTML, so a translation cannot inject markup.
 */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(key: MessageKey, params?: MessageParams): string {
    return this.i18n.t(key, params);
  }
}
