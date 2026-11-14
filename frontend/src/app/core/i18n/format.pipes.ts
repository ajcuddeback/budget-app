import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18nService } from './i18n.service';

/**
 * Locale-aware formatting through `Intl`, never by hand (ADR-0023). All impure for the same
 * reason as the translate pipe: the active locale is a signal read at transform time.
 */
@Pipe({ name: 'dateTime', pure: false })
export class DateTimePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(iso: string | null | undefined): string {
    return this.i18n.formatDateTime(iso);
  }
}

@Pipe({ name: 'dateOnly', pure: false })
export class DateOnlyPipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(iso: string | null | undefined): string {
    return this.i18n.formatDate(iso);
  }
}

/**
 * `{{ account.balance | money: account.currency }}` — an amount in its own currency.
 *
 * The amount stays the string the API sent all the way into `Intl` (ADR-0006). There is no
 * numeric overload on purpose: a `number` here would mean somebody had already parsed money.
 */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(amount: string | null | undefined, currency: string): string {
    return this.i18n.formatMoney(amount, currency);
  }
}

@Pipe({ name: 'currencyName', pure: false })
export class CurrencyNamePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(code: string): string {
    return this.i18n.currencyName(code);
  }
}

@Pipe({ name: 'localeName', pure: false })
export class LocaleNamePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(tag: string): string {
    return this.i18n.localeName(tag);
  }
}
