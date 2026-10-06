import { vi } from 'vitest';
import { signal, computed, WritableSignal } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { CurrentUser, Membership } from '../core/auth.models';
import { ApiError } from '../core/api-error';

/** Lets promise chains and the render they trigger finish. */
export async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve));
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve));
  await fixture.whenStable();
}

/** A throwaway value for tests; it opens nothing. */
export const PASSPHRASE = 'a long fixture passphrase';

export const OWNER_MEMBERSHIP: Membership = {
  householdId: 'h1',
  name: 'Rivera Household',
  baseCurrency: 'USD',
  role: 'OWNER',
  membershipId: 'm1',
  displayCurrency: null,
  locale: null,
};

export const OWNER: CurrentUser = {
  id: 'u1',
  email: 'alex@example.test',
  displayName: 'Alex Rivera',
  instanceAdmin: true,
  household: OWNER_MEMBERSHIP,
};

/** The slice of AuthService that pages read, as plain signals a test can drive. */
export interface FakeAuth {
  user: WritableSignal<CurrentUser | null>;
  loaded: WritableSignal<boolean>;
  isAuthenticated: ReturnType<typeof computed<boolean>>;
  membership: ReturnType<typeof computed<Membership | null>>;
  isOwner: ReturnType<typeof computed<boolean>>;
  ensureLoaded: ReturnType<typeof vi.fn>;
  refresh: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
  sessionEnded: ReturnType<typeof vi.fn>;
  login: ReturnType<typeof vi.fn>;
}

export function fakeAuth(user: CurrentUser | null = OWNER): FakeAuth {
  const current = signal<CurrentUser | null>(user);
  return {
    user: current,
    loaded: signal(true),
    isAuthenticated: computed(() => current() !== null),
    membership: computed(() => current()?.household ?? null),
    isOwner: computed(() => current()?.household?.role === 'OWNER'),
    ensureLoaded: vi.fn().mockResolvedValue(undefined),
    refresh: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    sessionEnded: vi.fn().mockResolvedValue(undefined),
    login: vi.fn().mockResolvedValue(undefined),
  };
}

export function apiError(
  status: number,
  code: string | null = null,
  params: Record<string, unknown> = {},
  fields: string[] = [],
): ApiError {
  return new ApiError(status, code, params, fields, 'corr-1');
}

export function click(root: HTMLElement, selector: string): void {
  const element = root.querySelector<HTMLElement>(selector);
  if (!element) {
    throw new Error(`nothing matches ${selector}`);
  }
  element.click();
}

export function buttonNamed(root: HTMLElement, name: RegExp | string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll('button')).find((button) =>
    typeof name === 'string' ? button.textContent?.includes(name) : name.test(button.textContent ?? ''),
  );
  if (!found) {
    throw new Error(`no button named ${String(name)}`);
  }
  return found;
}

export function type(root: HTMLElement, selector: string, value: string): void {
  const input = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector);
  if (!input) {
    throw new Error(`nothing matches ${selector}`);
  }
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('blur', { bubbles: true }));
}
