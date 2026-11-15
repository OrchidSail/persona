#!/usr/bin/env node
// Restore rotation only after instrumentation and its UiAutomation connection have ended.
import { spawnSync } from 'node:child_process';
import { withPreservedRotation } from './rotation-guard.mjs';

const args = process.argv.slice(2);
const supported = new Set(['--adb', '--device', '--mode', '--scenario', '--secondary-url', '--session-id', '--timeout-ms']);
const options = new Map();
for (let i = 0; i < args.length; i += 2) {
  if (!supported.has(args[i]) || !args[i + 1] || options.has(args[i])) throw new Error('Invalid or repeated check option');
  options.set(args[i], args[i + 1]);
}
const adb = options.get('--adb') || 'adb';
const device = options.get('--device');
const timeout = Number(options.get('--timeout-ms') || 180000);
if (!device || !Number.isInteger(timeout) || timeout < 1000 || timeout > 900000) {
  throw new Error('Usage: node run-watch-check.mjs --device <ADB serial> [--adb <path>] [--mode <mode>] [--scenario <names>] [--secondary-url <fixture URL>] [--session-id <test session>] [--timeout-ms <1000..900000>]');
}
function run(command) {
  const result = spawnSync(adb, ['-s', device, ...command], { windowsHide: true, timeout: 10000, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error('ADB preference operation failed');
  return result.stdout;
}
const command = ['shell', 'am', 'instrument', '-w', '-r'];
for (const [option, key, valid] of [
  ['--mode', 'mode', /^[a-z_]+$/],
  ['--scenario', 'scenario', /^[a-z_,]+$/],
  ['--secondary-url', 'secondary_url', /^[A-Za-z0-9._:/\[\]-]+$/],
  ['--session-id', 'session_id', /^[A-Za-z0-9._:-]+$/],
]) {
  const value = options.get(option);
  if (value != null) {
    if (!valid.test(value)) throw new Error(`Invalid ${option} value`);
    command.push('-e', key, value);
  }
}
command.push('dev.persona.watch.test/dev.persona.watch.WatchSmokeRunner');
try {
  withPreservedRotation(run, () => {
    const result = spawnSync(adb, ['-s', device, ...command], { windowsHide: true, timeout, maxBuffer: 4 * 1024 * 1024, encoding: 'utf8' });
    if (result.stdout) process.stdout.write(result.stdout);
    if (result.stderr) process.stderr.write(result.stderr);
    if (result.error || result.status !== 0) {
      // A killed host adb process can leave instrumentation on-device. Stop only
      // the app under this test so its disconnect cannot overwrite later cleanup.
      run(['shell', 'am', 'force-stop', 'dev.persona.watch']);
      throw new Error('Instrumentation interrupted or timed out; the test app was stopped');
    }
    if (!/INSTRUMENTATION_CODE:\s*-1\s*(?:\r?\n|$)/.test(result.stdout || '')) {
      throw new Error('Instrumentation did not report a passing result');
    }
  });
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
