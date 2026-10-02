import { Component, ChangeDetectionStrategy, computed, inject } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth.service';
import { TranslatePipe } from './core/i18n/t.pipe';
import { ThemeService } from './core/theme.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe],
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthService);

  protected readonly signedIn = this.auth.isAuthenticated;
  protected readonly themeLabel = computed(() =>
    this.theme.mode() === 'dark' ? 'theme.toLight' : 'theme.toDark',
  );

  constructor() {
    // A single-page app never reloads, so after each navigation focus moves to the new page's
    // content — otherwise a keyboard or screen-reader user is left on the link they just used.
    inject(Router)
      .events.pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => document.getElementById('main')?.focus({ preventScroll: true }));
  }

  protected skipToMain(): void {
    document.getElementById('main')?.focus();
  }

  protected toggleTheme(): void {
    this.theme.toggle();
  }

  protected signOut(): void {
    void this.auth.logout();
  }
}
