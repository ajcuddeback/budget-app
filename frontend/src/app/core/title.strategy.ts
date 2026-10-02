import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { I18nService } from './i18n/i18n.service';

/**
 * A route's `title` is a message key, not a sentence, so the document title is in the user's
 * language and a screen reader announces a new page after navigation (WCAG 2.4.2).
 */
@Injectable({ providedIn: 'root' })
export class TranslatedTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18nService);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const key = this.buildTitle(snapshot);
    const product = this.i18n.t('app.name');
    this.title.setTitle(key ? `${this.i18n.t(key)} · ${product}` : product);
  }
}
