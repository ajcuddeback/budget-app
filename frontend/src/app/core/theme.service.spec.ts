import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('persists the chosen mode', () => {
    const service = TestBed.inject(ThemeService);

    service.mode.set('dark');
    TestBed.tick();

    expect(localStorage.getItem('budget-owl.theme')).toBe('dark');
  });

  it('restores a stored preference over the system one', () => {
    localStorage.setItem('budget-owl.theme', 'dark');

    expect(TestBed.inject(ThemeService).mode()).toBe('dark');
  });

  it('ignores a stored value that is not a mode', () => {
    localStorage.setItem('budget-owl.theme', 'chartreuse');

    expect(['light', 'dark']).toContain(TestBed.inject(ThemeService).mode());
  });

  it('survives storage being unavailable', () => {
    const getItem = Storage.prototype.getItem;
    Storage.prototype.getItem = () => {
      throw new Error('site data blocked');
    };

    try {
      expect(['light', 'dark']).toContain(TestBed.inject(ThemeService).mode());
    } finally {
      Storage.prototype.getItem = getItem;
    }
  });

  it('toggles', () => {
    const service = TestBed.inject(ThemeService);
    const before = service.mode();

    service.toggle();

    expect(service.mode()).not.toBe(before);
  });
});
