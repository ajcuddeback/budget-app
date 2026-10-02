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
