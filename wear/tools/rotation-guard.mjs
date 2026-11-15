// UiAutomation disconnect can persist a watch's temporary charging rotation.
// Preserve the user's preference after the automation process has exited.
export function readRotationPreference(run) {
  const policy = run(['shell', 'wm', 'user-rotation']).toString('utf8').trim();
  const accelerometer = run(['shell', 'settings', 'get', 'system', 'accelerometer_rotation']).toString('utf8').trim();
  const rotation = run(['shell', 'settings', 'get', 'system', 'user_rotation']).toString('utf8').trim();
  if (!/^(free|lock [0-3])$/.test(policy) || !/^[01]$/.test(accelerometer) || !/^[0-3]$/.test(rotation)) {
    throw new Error('Cannot read an explicit rotation preference; refusing UI automation. Screenshot-only capture is still available.');
  }
  return { policy, accelerometer, rotation };
}

function equal(a, b) {
  return a.policy === b.policy && a.accelerometer === b.accelerometer && a.rotation === b.rotation;
}

export function withPreservedRotation(run, operation) {
  const original = readRotationPreference(run);
  const free = original.policy === 'free';
  if (original.accelerometer !== (free ? '1' : '0') || (!free && original.policy !== `lock ${original.rotation}`)) {
    throw new Error('Rotation preference is changing; retry after the watch settles.');
  }
  let value;
  let failure;
  try { value = operation(); }
  catch (error) { failure = error; }
  try {
    if (!equal(readRotationPreference(run), original)) {
      // Set the stored fallback before freeing sensor rotation. Restoring only "free"
      // can leave the leaked sideways fallback active while the sensor has no proposal.
      run(['shell', 'wm', 'user-rotation', 'lock', original.rotation]);
      if (free) run(['shell', 'wm', 'user-rotation', 'free']);
      if (!equal(readRotationPreference(run), original)) throw new Error('Rotation preference verification failed');
    }
  } catch (restoreFailure) {
    throw new AggregateError([failure, restoreFailure].filter(Boolean),
      `Rotation restoration failed; reconnect the watch and restore user rotation ${original.rotation}, ${free ? 'free' : 'locked'}.`);
  }
  if (failure) throw failure;
  return value;
}
