import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { isApiError } from './api-error';
import { CurrentUser, LoginRequest } from './auth.models';
import { I18nService } from './i18n/i18n.service';

/**
 * Who is signed in, as far as this browser knows.
 *
 * **This service holds no credential.** The web transport is an `HttpOnly` session cookie that
 * script cannot read (ADR-0004, ADR-0018); all the client ever has is the *profile* of whoever the
 * server says is signed in. Nothing here — and nothing anywhere in this app — writes a token, a
 * password or a session id to `localStorage`, `sessionStorage` or the console.
 *
 * The server is the only authority. The role held here decides what the UI offers, never what is
 * allowed: every write is checked again server-side.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);

  private readonly current = signal<CurrentUser | null>(null);
  private readonly loadedState = signal(false);
  private pending: Promise<void> | null = null;

  readonly user = this.current.asReadonly();
  readonly loaded = this.loadedState.asReadonly();
  readonly isAuthenticated = computed(() => this.current() !== null);
  readonly membership = computed(() => this.current()?.household ?? null);
  readonly isOwner = computed(() => this.membership()?.role === 'OWNER');

  /** Asks the server who we are, once. Later callers share the answer. */
  ensureLoaded(): Promise<void> {
    if (this.loadedState()) {
      return Promise.resolve();
    }
    this.pending ??= this.refresh();
    return this.pending;
  }

  /** Re-reads the profile — after a preference change, or after leaving the household. */
  async refresh(): Promise<void> {
    try {
      const user = await firstValueFrom(this.http.get<CurrentUser>('/api/auth/me'));
      await this.adopt(user);
    } catch (thrown) {
      // 401 is the ordinary "nobody is signed in". Before the first answer, any other failure
      // (server down) also leaves us signed out: a guard must decide, and "assume signed in" is
      // the unsafe default. After it, a transient failure must not sign a user out of the UI.
      if ((isApiError(thrown) && thrown.status === 401) || !this.loadedState()) {
        this.current.set(null);
      }
    } finally {
      this.loadedState.set(true);
      this.pending = null;
    }
  }

  async login(credentials: LoginRequest): Promise<CurrentUser> {
    const user = await firstValueFrom(this.http.post<CurrentUser>('/api/auth/login', credentials));
    await this.adopt(user);
    this.loadedState.set(true);
    return user;
  }

  /**
   * Ends the session on the server, then here. Succeeds from the user's point of view whatever
   * the server answers: a `401` means the session was already gone, and a network failure must
   * not leave someone staring at a household they asked to leave.
   */
  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post<void>('/api/auth/logout', null));
    } catch {
      // The server's answer changes nothing the user can act on.
    }
    this.current.set(null);
    await this.i18n.use(null);
    await this.router.navigate(['/login']);
  }

  /**
   * The server stopped honouring the session — idle timeout, absolute timeout, revoked from
   * another device, or removed from the household. Route to sign-in with a reason, not a crash.
   */
  async sessionEnded(): Promise<void> {
    if (this.current() === null) {
      return;
    }
    this.current.set(null);
    await this.router.navigate(['/login'], { queryParams: { reason: 'expired' } });
  }

  private async adopt(user: CurrentUser): Promise<void> {
    this.current.set({ ...user, household: user.household ?? null });
    // Language follows the member's own preference, and the platform's when they have none.
    await this.i18n.use(user.household?.locale);
  }
}
