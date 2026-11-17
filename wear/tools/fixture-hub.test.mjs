import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const token = 'persona-wear-fixture-only';
const messagePath = '/api/v1/sessions/fixture/assistant/messages';

async function fixture(t) {
  // Reserve a local ephemeral port; the child binds only loopback and never forwards requests.
  const reservation = createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const child = spawn(process.execPath, [fileURLToPath(new URL('./fixture-hub.mjs', import.meta.url)), '--port', String(port)],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(async () => {
    if (child.exitCode == null && child.signalCode == null) {
      const closed = once(child, 'close');
      child.kill('SIGTERM');
      await closed;
    }
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Synthetic fixture startup timed out')), 5000);
    let output = '';
    child.stdout.on('data', data => {
      output += data.toString();
      if (output.includes('Synthetic watch fixture listening')) { clearTimeout(timeout); resolve(); }
    });
    child.once('error', error => { clearTimeout(timeout); reject(error); });
    child.once('exit', () => { clearTimeout(timeout); reject(new Error('Synthetic fixture exited during startup')); });
  });
  return async (path, body, operation) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      method: body == null ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...(operation ? { 'X-Persona-Op-Id': operation } : {}) },
      body: body == null ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
    return { status: response.status, body: await response.json() };
  };
}

for (const order of [['sample-op-b', 'sample-op-a'], ['sample-op-a', 'sample-op-b']]) {
  test(`message receipt stays original across unkeyed and operation replays: ${order.join(' then ')}`, async t => {
    const request = await fixture(t);
    const body = { text: 'Synthetic message', client_msg_id: 'sample-message' };
    const accepted = await request(messagePath, body, 'sample-op-a');
    assert.deepEqual(accepted, { status: 200, body: { ok: true, state: 'accepted', client_msg_id: body.client_msg_id } });
    assert.deepEqual(await request(messagePath, body), accepted);
    for (const operation of [...order, ...order]) assert.deepEqual(await request(messagePath, body, operation), accepted);
    const audit = (await request('/__test')).body;
    assert.equal(audit.messageCounts.assistant, 4);
    assert.equal(audit.events.filter(event => event.action === 'message').length, 1);
    assert.equal(audit.events.some(event => event.action === 'message_replay'), true);
    assert.equal(audit.events.some(event => event.action === 'operation_replay'), true);
  });
}

test('new operation aliases retain their own conflict checks without changing earlier receipts', async t => {
  const request = await fixture(t);
  const first = { text: 'First synthetic message', client_msg_id: 'sample-first' };
  const second = { text: 'Second synthetic message', client_msg_id: 'sample-second' };
  const acceptedFirst = await request(messagePath, first, 'sample-op-a');
  const acceptedSecond = await request(messagePath, second, 'sample-op-b');
  assert.deepEqual(await request(messagePath, first, 'sample-op-c'), acceptedFirst);
  for (const operation of ['sample-op-a', 'sample-op-c']) {
    assert.deepEqual(await request(messagePath, second, operation), { status: 409, body: { code: 'operation_conflict' } });
    assert.deepEqual(await request(messagePath, first, operation), acceptedFirst);
  }
  assert.deepEqual(await request(messagePath, second, 'sample-op-b'), acceptedSecond);
  assert.deepEqual(await request(messagePath, { ...first, text: 'Changed synthetic text' }), { status: 409, body: { code: 'message_id_conflict' } });
  assert.equal((await request('/__test')).body.messageCounts.assistant, 6);
});

test('a message first accepted without an operation returns its original receipt after keyed retries', async t => {
  const request = await fixture(t);
  const body = { text: 'Initially unkeyed synthetic message', client_msg_id: 'sample-unkeyed' };
  const accepted = await request(messagePath, body);
  for (const operation of ['sample-op-b', 'sample-op-a', 'sample-op-b', 'sample-op-a']) {
    assert.deepEqual(await request(messagePath, body, operation), accepted);
  }
  assert.equal((await request('/__test')).body.messageCounts.assistant, 4);
});
