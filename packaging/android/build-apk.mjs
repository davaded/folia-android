import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// packaging/android/build-apk.mjs
// Package the complete upstream Web build without changing feature settings or renderer code.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const android = path.join(root, 'android');
const web = path.join(android, 'app/src/main/assets/web');
const task = process.argv.includes('--release') ? 'assembleRelease' : 'assembleDebug';
const originValue = process.env.FOLIA_ANDROID_API_ORIGIN?.trim() || '';
if (originValue) {
  const origin = new URL(originValue);
  if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password ||
      origin.pathname !== '/' || origin.search || origin.hash) {
    throw new Error('FOLIA_ANDROID_API_ORIGIN must be an HTTP(S) origin without credentials, path, query or fragment');
  }
}

const env = {
  ...process.env,
  // These stay on the APK's loopback origin; the native server forwards only to the chosen backend.
  VITE_NETEASE_API_BASE: '/netease',
  VITE_KUGOU_API_BASE: '/kugou',
  VITE_QQ_API_BASE: '/qq',
};

function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

run(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build']);
fs.mkdirSync(path.dirname(web), { recursive: true });
// This is a generated assets directory, never user music or source files.
fs.rmSync(web, { recursive: true, force: true });
fs.cpSync(path.join(root, 'dist'), web, { recursive: true });
fs.appendFileSync(path.join(web, 'runtime-config.js'),
  '\nwindow.__FOLIA_RUNTIME_CONFIG__.androidApiProxyOrigin = location.origin;\n');
for (const name of ['filesystem-compat.js', 'runtime-diagnostics.js']) {
  fs.copyFileSync(path.join(root, 'packaging/android', name), path.join(web, `android-${name}`));
}
const htmlPath = path.join(web, 'index.html');
const originalHtml = fs.readFileSync(htmlPath, 'utf8');
const scripts = '\n    <script src="/android-filesystem-compat.js"></script>\n' +
  '    <script src="/android-runtime-diagnostics.js"></script>\n';
fs.writeFileSync(htmlPath, originalHtml.replace('<head>', `<head>${scripts}`));

const commit = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' });
if (commit.status !== 0) throw new Error('Cannot determine source revision');
const entries = fs.readdirSync(web, { recursive: true }).filter(name =>
  fs.statSync(path.join(web, name)).isFile()).sort();
const checksums = Object.fromEntries(entries.map(name => [name.replaceAll(path.sep, '/'),
  createHash('sha256').update(fs.readFileSync(path.join(web, name))).digest('hex')]));
fs.writeFileSync(path.join(web, 'android-build.json'), JSON.stringify({
  upstreamCommit: commit.stdout.trim(),
  upstreamVersion: JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).version,
  backendConfigured: Boolean(originValue),
  createdAt: new Date().toISOString(),
  checksums,
}, null, 2));
console.log(`Packed ${entries.length} Web assets. Backend configured: ${Boolean(originValue)}.`);
if (process.argv.includes('--web-only')) process.exit(0);

const gradle = process.env.FOLIA_GRADLE_BIN || (process.platform === 'win32' ? 'gradlew.bat' : './gradlew');
run(gradle, ['--no-daemon', task], android);
