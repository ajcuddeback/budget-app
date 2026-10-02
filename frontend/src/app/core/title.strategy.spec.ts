import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { TitleStrategy, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TranslatedTitleStrategy } from './title.strategy';

@Component({ template: 'page' })
class Page {}

describe('TranslatedTitleStrategy', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'devices', title: 'title.devices', component: Page },
          { path: 'plain', component: Page },
        ]),
        { provide: TitleStrategy, useExisting: TranslatedTitleStrategy },
      ],
    });
  });

  it('titles the page from its route\'s message key', async () => {
    await RouterTestingHarness.create('/devices');

    expect(TestBed.inject(Title).getTitle()).toBe('Devices · Budget Owl');
  });

  it('falls back to the product name', async () => {
    await RouterTestingHarness.create('/plain');

    expect(TestBed.inject(Title).getTitle()).toBe('Budget Owl');
  });
});
