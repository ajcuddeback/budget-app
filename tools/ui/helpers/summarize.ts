import fs from 'node:fs';
import path from 'node:path';
import { OUT_DIR, readManifest, type Finding, type Shot } from './artifacts.js';

/**
 * Turn the raw manifest into REVIEW.md — the single file an agent reads after a run.
 *
 * The important property: every screenshot is listed by ABSOLUTE path, because the agent's
 * next step is to open each one with the Read tool. A run that captures perfect screenshots
 * and does not say where they are has validated nothing.
 */
const SEVERITY_ORDER = { critical: 0, serious: 1, moderate: 2, minor: 3 } as const;

function main(): void {
  const entries = readManifest();
  const shots = entries.filter((e): e is Shot => e.kind === 'screenshot');
  const findings = entries
    .filter((e): e is Finding => e.kind === 'finding')
    .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]);

  const lines: string[] = [];
  lines.push('# UI review', '');
  lines.push(`Run at ${new Date().toISOString()}`, '');

  lines.push('## Automated findings', '');
  if (findings.length === 0) {
    lines.push('None. No accessibility violations, console errors, failed requests, or layout overflow.');
    lines.push('');
    lines.push('**This does not mean the UI is correct** — it means nothing mechanically detectable is wrong.');
    lines.push('Whether it *looks* right is what the screenshots below are for.');
  } else {
    // The same defect surfaces once per viewport. Collapse them so the count reflects
    // distinct problems to fix, and list which viewports each was seen at.
    const grouped = new Map<string, { f: Finding; viewports: Set<string> }>();
    for (const f of findings) {
      const key = `${f.severity}|${f.category}|${f.summary}|${f.where ?? ''}`;
      const existing = grouped.get(key);
      if (existing) existing.viewports.add(f.viewport ?? '?');
      else grouped.set(key, { f, viewports: new Set([f.viewport ?? '?']) });
    }

    lines.push(`${grouped.size} distinct finding(s), most severe first.`, '');
    lines.push('| Severity | Category | Finding | Where | Viewports |');
    lines.push('|---|---|---|---|---|');
    for (const { f, viewports } of grouped.values()) {
      // Escape backslashes BEFORE pipes. Escaping only the pipe is incomplete: a value
      // ending in a backslash would escape our own escape character, so "a\\" + "|" renders
      // as a literal backslash followed by a LIVE pipe, splitting the table cell.
      // Newlines are collapsed for the same reason — one would end the table row.
      const esc = (v: string) =>
        v.replace(/\\/g, '\\\\').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
      lines.push(
        `| ${f.severity} | ${f.category} | ${esc(f.summary).slice(0, 160)} | ` +
          `${esc(f.where ?? '').slice(0, 100)} | ${[...viewports].join(', ')} |`,
      );
    }
  }
  lines.push('');

  lines.push('## Screenshots to review', '');
  if (shots.length === 0) {
    lines.push('None captured.');
  } else {
    lines.push('**Open each of these with the Read tool** — they render as images. Then judge:');
    lines.push('');
    lines.push('- Is the visual hierarchy clear? Does the primary action stand out?');
    lines.push('- Is spacing consistent, is anything clipped, overlapping, or misaligned?');
    lines.push('- Does the mobile view genuinely work, or is it a squashed desktop view?');
    lines.push('- Do empty, loading, and error states look intentional rather than broken?');
    lines.push('- Are money amounts formatted correctly and aligned for scanning?');
    lines.push('');
    for (const s of shots) {
      lines.push(`- \`${s.file}\``);
      lines.push(`  - ${s.spec} › ${s.label} · ${s.viewport} · ${s.url.slice(0, 100)}`);
    }
  }
  lines.push('');

  const reviewPath = path.join(OUT_DIR, 'REVIEW.md');
  fs.writeFileSync(reviewPath, lines.join('\n'));

  // Printed to stdout so the path is in the agent's transcript, not just on disk.
  console.log(`\nUI review written: ${reviewPath}`);
  console.log(`  screenshots: ${shots.length}   findings: ${findings.length}`);
  if (shots.length > 0) {
    console.log('\n  Read these images to validate the UI visually:');
    for (const s of shots) console.log(`    ${s.file}`);
  }
}

main();
