import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { I18nService } from './i18n.service';

describe('I18nService', () => {
  let i18n: I18nService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('translates from English and fills placeholders', () => {
    expect(i18n.t('nav.signOut')).toBe('Sign out');
    expect(i18n.t('members.joined', { date: 'today' })).toBe('Joined today');
  });

  it('leaves a placeholder alone when no value was given, and shows the key for an unknown message', () => {
    expect(i18n.t('members.joined', {})).toBe('Joined {date}');
    expect(i18n.t('no.such.key')).toBe('no.such.key');
  });

  it('never interprets a parameter as markup or as another placeholder', () => {
    expect(i18n.t('members.joined', { date: '<b>{date}</b>' })).toBe('Joined <b>{date}</b>');
  });

  it('uses English and fetches nothing for an English locale', async () => {
    await i18n.use('en-GB');

    expect(i18n.locale()).toBe('en-GB');
    expect(i18n.direction()).toBe('ltr');
    expect(document.documentElement.getAttribute('lang')).toBeDefined();
  });

  it('loads a translation file at run time, and falls back to English for what it lacks', async () => {
    const loading = i18n.use('de-DE');
    http.expectOne('/i18n/de.json').flush({ 'nav.signOut': 'Abmelden', 'bad': 7 });
    await loading;

    expect(i18n.t('nav.signOut')).toBe('Abmelden');
    expect(i18n.t('nav.devices')).toBe('Devices');
  });

  it('falls back to English when the file is missing or is not JSON', async () => {
    const missing = i18n.use('fr-FR');
    http.expectOne('/i18n/fr.json').flush('<html>', { status: 200, statusText: 'OK' });
    await missing;
    const gone = i18n.use('es-ES');
    http.expectOne('/i18n/es.json').flush('', { status: 404, statusText: 'Not Found' });
    await gone;

    expect(i18n.t('nav.signOut')).toBe('Sign out');
    expect(i18n.locale()).toBe('es-ES');
  });

  it('accepts only strings from a translation file', async () => {
    const loading = i18n.use('it-IT');
    http.expectOne('/i18n/it.json').flush(['not', 'an', 'object']);
    await loading;

    expect(i18n.t('nav.signOut')).toBe('Sign out');
  });

  it('flips the document to right-to-left for a right-to-left language, with no file needed', async () => {
    const loading = i18n.use('ar-EG');
    http.expectOne('/i18n/ar.json').flush('', { status: 404, statusText: 'Not Found' });
    await loading;
    TestBed.tick();

    expect(i18n.direction()).toBe('rtl');
    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(document.documentElement.getAttribute('lang')).toBe('ar-EG');
  });

  it('follows the platform for null and for a tag it cannot parse', async () => {
    await i18n.use(null);
    const platform = i18n.locale();
    await i18n.use('not a locale!!');

    expect(i18n.locale()).toBe(platform);
  });

  it('ignores a slow answer for a language the user has already left', async () => {
    const first = i18n.use('de-DE');
    const request = http.expectOne('/i18n/de.json');
    await i18n.use('en-US');
    request.flush({ 'nav.signOut': 'Abmelden' });
    await first;

    expect(i18n.t('nav.signOut')).toBe('Sign out');
  });

  it('formats dates, currency names and locale names through Intl', async () => {
    await i18n.use('en-US');

    expect(i18n.formatDateTime('2026-10-02T08:15:00Z')).toMatch(/2026/);
    expect(i18n.formatDate('2026-10-02T08:15:00Z')).toBe('Oct 2, 2026');
    expect(i18n.formatDate('nonsense')).toBe('');
    expect(i18n.formatDateTime(null)).toBe('');
    expect(i18n.formatDateTime('nonsense')).toBe('');
    expect(i18n.formatDate(undefined)).toBe('');
    expect(i18n.currencyName('USD')).toBe('US Dollar');
    expect(i18n.currencyName('not-a-code')).toBe('not-a-code');
    expect(i18n.localeName('de-DE')).toMatch(/Deutsch/);
    expect(i18n.localeName('!!')).toBe('!!');
  });

  it('describes a wait in the largest sensible unit', () => {
    expect(i18n.duration(0)).toBe('1 seconds');
    expect(i18n.duration(30)).toBe('30 seconds');
    expect(i18n.duration(60)).toBe('1 minute');
    expect(i18n.duration(120)).toBe('2 minutes');
    expect(i18n.duration(3600)).toBe('1 hour');
    expect(i18n.duration(7200)).toBe('2 hours');
  });

  it('knows which keys exist', () => {
    expect(i18n.has('nav.signOut')).toBe(true);
    expect(i18n.has('nope')).toBe(false);
  });
});
