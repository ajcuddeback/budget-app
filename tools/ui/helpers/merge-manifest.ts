/**
 * Folds a capture run's per-shot files into `userguide/images/manifest.json`.
 *
 * Run once by `tools/userguide-capture.sh` after Playwright exits. It is a separate entry point
 * rather than a Playwright teardown so that the merge happens even when the run fails partway:
 * the images it did write are on disk, and an image on disk with no manifest entry now fails
 * `tools/userguide-check.sh`.
 */
import { mergeManifest } from './doc-capture.js';

console.log(mergeManifest());
