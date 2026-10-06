import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CurrencyNamePipe, DateOnlyPipe, DateTimePipe, LocaleNamePipe } from './format.pipes';
import { TranslatePipe } from './t.pipe';
import { I18nService } from './i18n.service';

describe('formatting pipes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  it('delegate to the locale-aware service', async () => {
    await TestBed.inject(I18nService).use('en-US');
    TestBed.runInInjectionContext(() => {
      expect(new DateOnlyPipe().transform('2026-10-02T08:15:00Z')).toBe('Oct 2, 2026');
      expect(new DateTimePipe().transform('2026-10-02T08:15:00Z')).toMatch(/Oct 2, 2026/);
      expect(new CurrencyNamePipe().transform('EUR')).toBe('Euro');
      expect(new LocaleNamePipe().transform('fr-FR')).toMatch(/français/);
      expect(new TranslatePipe().transform('nav.signOut')).toBe('Sign out');
    });
  });
});
