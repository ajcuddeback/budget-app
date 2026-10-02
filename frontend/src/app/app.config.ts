import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter, TitleStrategy, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { apiErrorInterceptor, credentialsInterceptor } from './core/http.interceptors';
import { TranslatedTitleStrategy } from './core/title.strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: TitleStrategy, useExisting: TranslatedTitleStrategy },
    provideHttpClient(
      withInterceptors([credentialsInterceptor, apiErrorInterceptor]),
      // The names Spring's CookieCsrfTokenRepository issues and reads (SecurityConfig). They are
      // also Angular's defaults, and are spelled out so a change on either side is a visible diff
      // rather than a silent 403 on every write.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
  ],
};
