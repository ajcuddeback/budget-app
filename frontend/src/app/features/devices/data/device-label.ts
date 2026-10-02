export interface BrowserSummary {
  browser: string;
  system: string;
}

/**
 * A browser's `User-Agent` is what the server records as the device label, trimmed to 100
 * characters, and nobody can tell their devices apart from `Mozilla/5.0 (X11; Linux x86_64)
 * AppleWebKit/537.36 (KHTML, li…`. This pulls out the two things a person recognises.
 *
 * Deliberately modest — a handful of well-known tokens, checked in the order that makes the
 * overlapping ones (Edge and Opera also say "Chrome") come out right — and it returns `null`
 * rather than guess, in which case the caller shows the label as recorded. The label is
 * attacker-influenced text; it is only ever rendered as text, never as HTML.
 */
export function summariseUserAgent(label: string): BrowserSummary | null {
  const browser = firstMatch(label, [
    [/Edg(e|A|iOS)?\//, 'Edge'],
    [/OPR\/|Opera/, 'Opera'],
    [/Firefox\/|FxiOS\//, 'Firefox'],
    [/Chrome\/|CriOS\//, 'Chrome'],
    [/Safari\//, 'Safari'],
  ]);
  const system = firstMatch(label, [
    [/Windows/, 'Windows'],
    [/Android/, 'Android'],
    [/iPhone|iPad|iPod/, 'iOS'],
    [/Mac OS X|Macintosh/, 'macOS'],
    [/CrOS/, 'ChromeOS'],
    [/Linux|X11/, 'Linux'],
  ]);
  return browser && system ? { browser, system } : null;
}

function firstMatch(text: string, rules: readonly (readonly [RegExp, string])[]): string | null {
  return rules.find(([pattern]) => pattern.test(text))?.[1] ?? null;
}
