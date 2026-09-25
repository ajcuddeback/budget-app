import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    await TestBed.configureTestingModule({ imports: [App] }).compileComponents();
  });

  it('creates the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the product name', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('Budget Owl');
  });

  it('stamps the theme on the root element so the tokens can key off it', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    expect(['light', 'dark']).toContain(document.documentElement.getAttribute('data-theme'));
  });

  it('toggles between light and dark', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const before = document.documentElement.getAttribute('data-theme');

    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).not.toBe(before);
  });
});
