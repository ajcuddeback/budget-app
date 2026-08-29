import fs from 'node:fs';
import path from 'node:path';

export const OUT_DIR = path.resolve(process.env.UI_OUT_DIR ?? 'artifacts');
const MANIFEST = path.join(OUT_DIR, 'manifest.jsonl');

export type Shot = {
  kind: 'screenshot';
  file: string;
  spec: string;
  label: string;
  url: string;
  viewport: string;
};

export type Finding = {
  kind: 'finding';
  spec: string;
  severity: 'critical' | 'serious' | 'moderate' | 'minor';
  category: 'a11y' | 'console' | 'network' | 'layout';
  summary: string;
  detail?: string;
  where?: string;
  viewport?: string;
};

export type Entry = Shot | Finding;

export function resetArtifacts(): void {
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

export function record(entry: Entry): void {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.appendFileSync(MANIFEST, JSON.stringify(entry) + '\n');
}

export function readManifest(): Entry[] {
  // Read-and-catch rather than exists-then-read; see the note in doc-capture.ts.
  let raw: string;
  try {
    raw = fs.readFileSync(MANIFEST, 'utf8');
  } catch {
    return [];
  }
  return raw
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as Entry);
}
