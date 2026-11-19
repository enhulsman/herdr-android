// The release signing certificate's SHA-256 digest, read at build time from
// docs/release-signing-fingerprint.txt: the one line that is not a comment.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export function releaseFingerprint() {
  const file = resolve(process.cwd(), '../docs/release-signing-fingerprint.txt');
  const lines = readFileSync(file, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
  if (lines.length !== 1 || !/^[0-9a-f]{64}$/.test(lines[0])) {
    throw new Error(`${file}: expected one 64-character hex line, found ${lines.length} lines`);
  }
  return lines[0];
}
