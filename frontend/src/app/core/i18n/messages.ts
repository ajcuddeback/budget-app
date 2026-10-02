import english from '../../../i18n/en.json';

/**
 * English is the source language and is compiled into the bundle, so the app is never without
 * words and a missing translation falls back to it rather than showing a key (ADR-0023).
 *
 * Every other language is a JSON file of the same shape, served from `/i18n/<language>.json` and
 * loaded at run time. They are authored through a translation platform, never by hand, and never
 * need a backend release.
 *
 * The key type is derived from the English file, so a template that asks for a key that does not
 * exist — or a change that deletes one — fails to compile.
 */
export const ENGLISH: Readonly<Record<string, string>> = english;

export type MessageKey = keyof typeof english;

export function isMessageKey(candidate: string): candidate is MessageKey {
  return Object.hasOwn(english, candidate);
}

export type MessageParams = Readonly<Record<string, string | number>>;
