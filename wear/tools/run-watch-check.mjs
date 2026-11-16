#!/usr/bin/env node
// Restore rotation only after instrumentation and its UiAutomation connection have ended.
import { spawnSync, spawn as spawnChildProcess } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { withPreservedRotationAsync } from './rotation-guard.mjs';

const TEST_PACKAGE = 'dev.persona.watch.test';
const TARGET_PACKAGE = 'dev.persona.watch';
const pollingCell = new Int32Array(new SharedArrayBuffer(4));

/** Keep Node responsive to interruption while the host ADB child owns instrumentation. */
export function runInstrumentation(file, args, { timeout, maxBuffer }, { spawnChild = spawnChildProcess, signals = process } = {}) {
  return new Promise(resolve => {
    const output = [], errors = [];
    let child, failure, timer, killTimer, settled = false, bytes = 0;
    const stop = error => {
      failure ??= error;
      child?.kill('SIGTERM');
      killTimer ??= setTimeout(() => child?.kill('SIGKILL'), 1000);
    };
    const interrupt = () => stop(Object.assign(new Error('Instrumentation interrupted'), { code: 'EINTR' }));
    const finish = (status, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer); clearTimeout(killTimer);
      signals.off('SIGINT', interrupt); signals.off('SIGTERM', interrupt);
      resolve({ status, signal, error: failure, stdout: Buffer.concat(output).toString('utf8'), stderr: Buffer.concat(errors).toString('utf8') });
    };
    const collect = target => data => {
      bytes += data.length;
      if (bytes > maxBuffer) stop(Object.assign(new Error('Instrumentation output limit exceeded'), { code: 'ENOBUFS' }));
      else target.push(Buffer.from(data));
    };
    try {
      child = spawnChild(file, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      signals.on('SIGINT', interrupt); signals.on('SIGTERM', interrupt);
      child.stdout.on('data', collect(output)); child.stderr.on('data', collect(errors));
      child.once('error', error => { failure ??= error; finish(null, null); });
      child.once('close', finish);
      timer = setTimeout(() => stop(Object.assign(new Error('Instrumentation timed out'), { code: 'ETIMEDOUT' })), timeout);
    } catch (error) { failure = error; finish(null, null); }
  });
}

/** Unknown/failed dumps cannot establish teardown. Never print their potentially private contents. */
export function automationActive(processes, accessibility) {
  if (!/^ACTIVITY MANAGER RUNNING PROCESSES\b/m.test(processes) ||
      !/^ACCESSIBILITY MANAGER\b/m.test(accessibility)) throw new Error('Unrecognized automation status');
  const hasInstrumentation = /Active instrumentation:|Instrumentation #/i.test(processes);
  const hasAutomation = /Ui\s*Automation/i.test(accessibility.replace(/mUiAutomationService\s*=\s*null/g, ''));
  return hasInstrumentation || hasAutomation;
}

/** Android can host instrumentation in the target process. Only this exact runner/target may be stopped. */
export function hasOwnedInstrumentation(processes) {
  return processes.split(/^\s*Instrumentation #\d+:/m).slice(1).some(block =>
    /mClass=ComponentInfo\{dev\.persona\.watch\.test\/dev\.persona\.watch\.WatchSmokeRunner\}/.test(block) &&
    /^\s*mTargetProcesses=\[dev\.persona\.watch\]\s*$/m.test(block));
}

export function waitForAutomationExit(run, {
  now = () => performance.now(),
  wait = milliseconds => Atomics.wait(pollingCell, 0, 0, milliseconds),
  timeoutMs = 15000,
  stopOwnedTarget = false,
} = {}) {
  const deadline = now() + timeoutMs;
  let targetStopped = false;
  for (;;) {
    const remaining = deadline - now();
    if (remaining <= 0) throw new Error('Instrumentation or UiAutomation did not finish');
    const processes = run(['shell', 'dumpsys', 'activity', 'processes'], Math.min(remaining, 2000)).toString('utf8');
    const accessibility = run(['shell', 'dumpsys', 'accessibility'], Math.min(Math.max(deadline - now(), 1), 2000)).toString('utf8');
    if (!automationActive(processes, accessibility)) return;
    if (stopOwnedTarget && !targetStopped && hasOwnedInstrumentation(processes)) {
      run(['shell', 'am', 'force-stop', TARGET_PACKAGE], Math.min(Math.max(deadline - now(), 1), 2000));
      targetStopped = true;
    }
    const delay = Math.min(deadline - now(), 100);
    if (delay > 0) wait(delay);
  }
}

/** Dependency injection is for host-side tests; importing this module never invokes ADB. */
export async function runWatchCheck(args, {
  spawn = spawnSync,
  instrument = spawn === spawnSync ? runInstrumentation : spawn,
  stdout = value => process.stdout.write(value),
  stderr = value => process.stderr.write(value),
  cleanup = {},
} = {}) {
  try {
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
      throw new Error('A device and a timeout from 1000 to 900000 milliseconds are required');
    }
    function run(command, commandTimeout = 10000) {
      const result = spawn(adb, ['-s', device, ...command], { windowsHide: true, timeout: commandTimeout, maxBuffer: 1024 * 1024 });
      if (result.error || result.signal || result.status !== 0) throw new Error('ADB preference or cleanup operation failed');
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
    command.push(`${TEST_PACKAGE}/dev.persona.watch.WatchSmokeRunner`);
    let passed = false;
    await withPreservedRotationAsync(run, async () => {
      try {
        const result = await instrument(adb, ['-s', device, ...command], { windowsHide: true, timeout, maxBuffer: 4 * 1024 * 1024, encoding: 'utf8' });
        if (result.stdout) stdout(result.stdout);
        if (result.stderr) stderr(result.stderr);
        if (result.error || result.signal || result.status !== 0) throw new Error('Instrumentation interrupted or timed out');
        if (!/INSTRUMENTATION_CODE:\s*-1\s*(?:\r?\n|$)/.test(result.stdout || '') || /(?:^|\n)(?:INSTRUMENTATION_RESULT:\s*stream=)?FAIL:/.test(result.stdout || '')) {
          throw new Error('Instrumentation did not report a passing result');
        }
        passed = true;
      } finally {
        // Stopping this package alone is not proof that Android has disconnected UiAutomation.
        if (!passed) run(['shell', 'am', 'force-stop', TEST_PACKAGE]);
      }
    }, () => waitForAutomationExit(run, { ...cleanup, stopOwnedTarget: !passed }));
    return 0;
  } catch (error) {
    stderr(`${error.message}\n`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await runWatchCheck(process.argv.slice(2));
}
