import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { startFakeNode, until } from '../../runtime-sdk/testing/fake-node.js'
import { cleanUserText, isCompactCommand, stepsToItems, transcriptStatus } from '../lib/map.js'
import { createAgyRuntime } from '../lib/runtime.js'

const RAW = '11111111-2222-4333-8444-555555555555'
const SESSION = `agy-${RAW}`
const FAKE = fileURLToPath(new URL('./fake-acp-rewind.mjs', import.meta.url))
const AT_SILENT = '2026-01-02T12:00:00Z'

const initialSteps = [
  { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT_SILENT, content: 'Inspect context' },
  {
    step_index: 1, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: AT_SILENT,
    model: 'gemini-4-neon-high', content: 'Done.',
    input_tokens: 120000, cache_read_tokens: 210000, output_tokens: 450, context_window: 1048576,
  },
]

async function fixture(t, promptPlan) {
  const home = mkdtempSync(join(tmpdir(), 'agy-compact-'))
  const config = join(home, 'config')
  const assistantDir = join(home, '.persona', 'assistant')
  const logs = join(config, 'brain', RAW, '.system_generated', 'logs')
  mkdirSync(assistantDir, { recursive: true })
  mkdirSync(logs, { recursive: true })
  const transcript = join(logs, 'transcript_full.jsonl')
  writeFileSync(transcript, initialSteps.map((s) => JSON.stringify(s)).join('\n') + '\n')
  writeFileSync(join(home, 'calls.jsonl'), '')
  writeFileSync(join(home, 'trajectory.json'), JSON.stringify({
    lazyService: true,
    status: 'CASCADE_RUN_STATUS_IDLE',
    trajectory: { cascadeId: RAW, steps: [] },
    promptPlan,
  }))
  const db = new DatabaseSync(join(config, 'conversation_summaries.db'))
  db.exec('CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, status TEXT, not_fully_idle INTEGER, killed INTEGER, step_count INTEGER, title TEXT, workspace_uris TEXT, last_modified_time TEXT)')
  db.prepare('INSERT INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)')
    .run(RAW, 'CASCADE_RUN_STATUS_IDLE', initialSteps.length, 'Main', JSON.stringify([pathToFileURL(assistantDir).href]), new Date().toISOString())
  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home, agyDir: config, agyBin: process.execPath, agyArgs: [FAKE, home, 'acp'],
    personadBin: process.execPath, socketPath: node.path, log: () => {},
    transient401RetryDelayMs: 10, exportPollMs: 20,
  })
  t.after(async () => { await runtime.stop(); db.close(); rmSync(home, { recursive: true, force: true }) })
  runtime.start()
  await node.ready
  const row = () => node.last('runtime.sessions')?.sessions.find((entry) => entry.session_id === SESSION)
  await until('session listed', () => row()?.context_tokens === 330000)
  const calls = () => readFileSync(join(home, 'calls.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse)
  const items = () => node.peer.call('session.items', { session_id: SESSION })
  const appendCheckpoint = () => {
    const step = { step_index: 2, type: 'CHECKPOINT', source: 'SYSTEM', status: 'DONE', created_at: new Date().toISOString() }
    writeFileSync(transcript, [...initialSteps, step].map((s) => JSON.stringify(s)).join('\n') + '\n')
    db.prepare('UPDATE conversation_summaries SET step_count = ?, last_modified_time = ? WHERE conversation_id = ?')
      .run(3, new Date().toISOString(), RAW)
  }
  return { node, row, calls, items, appendCheckpoint, idle: () => runtime.idle() }
}

test('/compact with CHECKPOINT export does not retry as an empty response and resets context_tokens', async (t) => {
  const { node, row, calls, items, idle } = await fixture(t, [{ export: true, skipInput: true, checkpoint: true }])
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact', assistant: true })
  await until('compaction settled', () => idle() && row()?.context_tokens === undefined)
  const prompts = calls().filter((call) => call.method === 'session/prompt')
  assert.equal(prompts.length, 1)
  assert.deepEqual(prompts[0].params.prompt, [{ type: 'text', text: '/compact' }])
  assert.equal(row().turn_error, undefined)
  const page = await items()
  assert.deepEqual(page.items.at(-1), {
    ...page.items.at(-1), kind: 'notice', role: 'system', text: '上下文已压缩',
  })
  assert.equal(page.items.some((item) => /no response returned/i.test(item.text || '')), false)
})

test('silent /compact does not retry, emits no empty-response error, and picks up a delayed CHECKPOINT', async (t) => {
  const { node, row, calls, items, appendCheckpoint, idle } = await fixture(t, [{}])
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact', assistant: true })
  await until('silent /compact settled', idle)
  assert.equal(calls().filter((call) => call.method === 'session/prompt').length, 1)
  assert.equal(row().turn_error, undefined)
  // No phantom unexported /compact user bubble when agy has not exported anything yet.
  assert.equal((await items()).items.some((item) => item.text === '/compact'), false)
  // A delayed CHECKPOINT written after prompt return is picked up by export polling and clears context_tokens.
  appendCheckpoint()
  await until('delayed checkpoint observed', () => row()?.context_tokens === undefined)
  const page = await items()
  assert.equal(page.items.at(-1).kind, 'notice')
  assert.equal(page.items.at(-1).text, '上下文已压缩')
})

test('a genuine ACP error during /compact is still published as turn_error', async (t) => {
  const { node, row, calls, idle } = await fixture(t, [{ error: 'Compaction failed upstream' }])
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact', assistant: true })
  await until('compact error published', () => idle() && row()?.turn_error === 'Compaction failed upstream')
  assert.equal(calls().filter((call) => call.method === 'session/prompt').length, 1)
})

test('bare /compact USER_INPUT maps to system notice instead of user bubble and preserves lastText and client_msg_id', () => {
  const steps = [
    { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT_SILENT, content: '<USER_REQUEST>\nHello\n</USER_REQUEST>' },
    { step_index: 1, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: AT_SILENT, content: 'Previous reply' },
    { step_index: 2, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT_SILENT, content: '<USER_REQUEST>\n/compact\n</USER_REQUEST>' },
    { step_index: 3, type: 'CHECKPOINT', source: 'SYSTEM', status: 'DONE', created_at: AT_SILENT, content: '# Resuming from a compaction' },
    { step_index: 4, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT_SILENT, content: '<USER_REQUEST>\nNext message\n</USER_REQUEST>' },
  ]
  const ordinals = []
  const full = stepsToItems(steps, {
    full: false,
    clientMsgIdFor: (_idx, text, ord) => {
      ordinals.push([ord, text])
      if (ord === 2) return 'manual-compact-1'
      if (ord === 3) return 'next-msg-id'
      return undefined
    },
  })
  assert.equal(full.items.some((item) => item.role === 'user' && item.text === '/compact'), false)
  const notices = full.items.filter((item) => item.kind === 'notice')
  assert.deepEqual(notices.map((n) => ({ role: n.role, text: n.text, client_msg_id: n.client_msg_id })), [
    { role: 'system', text: '压缩上下文', client_msg_id: 'manual-compact-1' },
    { role: 'system', text: '上下文已压缩', client_msg_id: undefined },
  ])
  assert.deepEqual(ordinals, [[1, 'Hello'], [2, '/compact'], [3, 'Next message']])
  assert.equal(full.items.at(-1).client_msg_id, 'next-msg-id')

  const fullBeforeNext = stepsToItems(steps.slice(0, 4), { full: false })
  const summaryBeforeNext = stepsToItems(steps.slice(0, 4), { full: false, summaryOnly: true })
  assert.equal(fullBeforeNext.lastText, 'Previous reply')
  assert.equal(summaryBeforeNext.lastText, 'Previous reply')
})

test('live and exported /compact with USER_INPUT never emits a /compact user bubble', async (t) => {
  const { node, row, items, idle } = await fixture(t, [{ export: true, checkpoint: true }])
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact', assistant: true })
  await until('compaction settled', () => idle() && row()?.context_tokens === undefined)
  const page = await items()
  assert.equal(page.items.some((item) => item.role === 'user' && item.text === '/compact'), false)
  const notices = page.items.filter((item) => item.kind === 'notice').map((item) => item.text)
  assert.deepEqual(notices, ['压缩上下文', '上下文已压缩'])
})

test('cleanUserText strips phantom trailing /compact appended by agy CLI even when middle text is truncated', () => {
  const fullMention = '<USER_REQUEST>\nFix agy /compact handling in PR #14755.\nStatus: CLEAN\n/compact\n</USER_REQUEST>'
  assert.equal(cleanUserText(fullMention), 'Fix agy /compact handling in PR #14755.\nStatus: CLEAN')

  const truncatedMention = '<USER_REQUEST>\nContent of persona-context:///snapshot.txt:\n<persona-context>\nsnapshot\n</persona-context>\nHeader\n<truncated 12684 bytes>\nStatus: CLEAN\n/compact\n</USER_REQUEST>'
  assert.equal(cleanUserText(truncatedMention), 'Header\n<truncated 12684 bytes>\nStatus: CLEAN')

  const attachedImg = '<USER_REQUEST>\nFix /compact\n<attached_images>\n/tmp/a.png\n</attached_images>/compact\n</USER_REQUEST>'
  assert.equal(cleanUserText(attachedImg), 'Fix /compact\n<attached_images>\n/tmp/a.png\n</attached_images>')

  const bareCompact = '<USER_REQUEST>\n/compact\n</USER_REQUEST>'
  assert.equal(cleanUserText(bareCompact), '/compact')

  const genuineEnding = '<USER_REQUEST>\nPlease run this next\n/compact\n</USER_REQUEST>'
  assert.equal(cleanUserText(genuineEnding), 'Please run this next\n/compact')
})

const AT = '2026-11-27T01:00:00Z'
const AT2 = '2026-11-27T01:15:00Z'
const AT3 = '2026-11-27T01:15:10Z'

test('isCompactCommand matches /compact with or without trailing instructions', () => {
  assert.equal(isCompactCommand('/compact'), true)
  assert.equal(isCompactCommand('  /COMPACT keep test failures  '), true)
  assert.equal(isCompactCommand('/compaction'), false)
  assert.equal(isCompactCommand('please run /compact'), false)
})

test('stepsToItems maps /compact and CHECKPOINT to divider notices without clearing lastText', () => {
  const steps = [
    { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT, content: '<USER_REQUEST>\nhi\n</USER_REQUEST>' },
    { step_index: 1, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: AT, content: 'Previous reply.', input_tokens: 4000, cache_read_tokens: 475000, output_tokens: 150 },
    { step_index: 2, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT2, content: '<USER_REQUEST>\n/compact\n</USER_REQUEST>', client_msg_id: 'cmp-1' },
    { step_index: 3, type: 'CHECKPOINT', source: 'SYSTEM', status: 'DONE', created_at: AT3, content: '# Resuming from compaction', input_tokens: 3797, cache_read_tokens: 478978, output_tokens: 6696 },
  ]
  const parsed = stepsToItems(steps)
  assert.equal(parsed.lastText, 'Previous reply.')
  assert.equal(parsed.usage.contextTokens, 0)
  assert.equal(parsed.usage.outputTokens, 150)
  assert.equal(parsed.items.some((it) => it.role === 'user' && it.text === '/compact'), false)
  const cmd = parsed.items.find((it) => it.role === 'system' && it.kind === 'notice' && it.text === '压缩上下文')
  assert.ok(cmd, 'expected CompactNotice item for /compact')
  assert.equal(cmd.client_msg_id, 'cmp-1')
  const cp = parsed.items.find((it) => it.role === 'system' && it.kind === 'notice' && it.text === '上下文已压缩')
  assert.ok(cp, 'expected CompactedNotice item for CHECKPOINT')
  assert.deepEqual(cp.compact, { pre_tokens: 482775 })

  const summary = stepsToItems(steps, { summaryOnly: true })
  assert.equal(summary.lastText, 'Previous reply.')
  assert.equal(summary.usage.contextTokens, 0)

  assert.equal(transcriptStatus(steps, { status: 'CASCADE_RUN_STATUS_IDLE', step_count: 4 }), 'done')
})

test('sending /compact with CHECKPOINT export does not retry or report No response returned', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'agy-compact-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const agyDir = join(dir, 'agy')
  const cwd = join(dir, 'work')
  const logsDir = join(agyDir, 'brain', RAW, '.system_generated', 'logs')
  const cacheDir = join(agyDir, 'cache')
  mkdirSync(logsDir, { recursive: true })
  mkdirSync(cacheDir, { recursive: true })
  mkdirSync(cwd, { recursive: true })

  const transcriptPath = join(logsDir, 'transcript_full.jsonl')
  const initialSteps = [
    { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT, content: '<USER_REQUEST>\nhi\n</USER_REQUEST>' },
    { step_index: 1, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: AT, content: 'Before compact.', input_tokens: 4000, cache_read_tokens: 246000, output_tokens: 100 },
  ]
  writeFileSync(transcriptPath, initialSteps.map((s) => JSON.stringify(s)).join('\n') + '\n')

  const dbPath = join(agyDir, 'conversation_summaries.db')
  const db = new DatabaseSync(dbPath)
  db.exec('CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, status TEXT, not_fully_idle NUMERIC, killed NUMERIC, step_count INTEGER, title TEXT, workspace_uris TEXT, last_modified_time TEXT)')
  db.prepare('INSERT INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)').run(RAW, 'CASCADE_RUN_STATUS_IDLE', 2, 'Compact Test', JSON.stringify([`file://${cwd}`]), AT)
  db.close()

  writeFileSync(join(cacheDir, 'conversation_metadata.json'), JSON.stringify({
    conversations: { [RAW]: { title: 'Compact Test', workspace: cwd, last_modified: AT } },
  }))

  const callsPath = join(dir, 'acp-calls.jsonl')
  const fakeScriptPath = join(dir, 'fake-agy.mjs')
  const fakeBin = join(dir, 'fake-agy')
  const fakeScript = `import { appendFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { DatabaseSync } from 'node:sqlite'

if (process.argv.includes('models')) { console.log('test-model\\tTest Model'); process.exit(0) }
const send = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\\n')
const rl = createInterface({ input: process.stdin })
rl.on('line', (line) => {
  if (!line.trim()) return
  const msg = JSON.parse(line)
  if (msg.method === 'initialize') return send({ id: msg.id, result: { protocolVersion: 1 } })
  if (msg.method === 'session/load') return send({ id: msg.id, result: { currentModeId: 'bypassPermissions' } })
  if (msg.method === 'session/prompt') {
    const text = (msg.params?.prompt || []).map((b) => b.text || '').join('\\n').trim()
    appendFileSync(${JSON.stringify(callsPath)}, JSON.stringify({ text }) + '\\n')
    const compactSteps = [
      { step_index: 2, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: ${JSON.stringify(AT2)}, content: '<USER_REQUEST>\\n/compact\\n</USER_REQUEST>' },
      { step_index: 3, type: 'CHECKPOINT', source: 'SYSTEM', status: 'DONE', created_at: ${JSON.stringify(AT3)}, content: '# Resuming from compaction', input_tokens: 3000, cache_read_tokens: 247000, output_tokens: 5000 },
    ]
    appendFileSync(${JSON.stringify(transcriptPath)}, compactSteps.map((s) => JSON.stringify(s)).join('\\n') + '\\n')
    const d = new DatabaseSync(${JSON.stringify(dbPath)})
    d.prepare('UPDATE conversation_summaries SET step_count = ?, last_modified_time = ? WHERE conversation_id = ?').run(4, ${JSON.stringify(AT3)}, ${JSON.stringify(RAW)})
    d.close()
    return send({ id: msg.id, result: { stopReason: 'end_turn' } })
  }
  send({ id: msg.id, result: {} })
})
`
  writeFileSync(fakeScriptPath, fakeScript)
  writeFileSync(fakeBin, `#!/bin/sh\nexec "${process.execPath}" "${fakeScriptPath}" "$@"\n`)
  chmodSync(fakeBin, 0o755)

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home: dir,
    agyDir,
    agyBin: fakeBin,
    socketPath: node.path,
    exportPollMs: 20,
    transient401RetryDelayMs: 20,
    retryMs: 50,
  })
  runtime.start()
  t.after(async () => { await runtime.stop() })

  await node.ready
  const row = () => node.last('runtime.sessions')?.sessions?.find((s) => s.session_id === SESSION)
  await until('runtime registered', () => row()?.context_tokens === 250000)
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact' })
  await until('compaction settled', () => row()?.status === 'done' && !row()?.context_tokens)

  // Give retry window a moment to ensure no spurious retry occurs.
  await new Promise((r) => setTimeout(r, 80))
  const sentPrompts = readFileSync(callsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  assert.equal(sentPrompts.length, 1, `expected exactly 1 /compact prompt, got ${sentPrompts.length}`)
  assert.equal(sentPrompts[0].text, '/compact')

  assert.equal(row().turn_error, undefined)
  assert.equal(row().last_text, 'Before compact.')

  const page = await node.peer.call('session.items', { session_id: SESSION, limit: 20 })
  assert.equal(page.items.some((it) => it.role === 'user' && it.text === '/compact'), false)
  assert.ok(page.items.some((it) => it.role === 'system' && it.kind === 'notice' && it.text === '压缩上下文'))
  const cp = page.items.find((it) => it.role === 'system' && it.kind === 'notice' && it.text === '上下文已压缩')
  assert.ok(cp)
  assert.deepEqual(cp.compact, { pre_tokens: 250000 })
})

async function setupCompactRuntime(t, { summaryStepCount = 2, promptHandler }) {
  const dir = mkdtempSync(join(tmpdir(), 'agy-compact-settle-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const agyDir = join(dir, 'agy')
  const cwd = join(dir, 'work')
  const logsDir = join(agyDir, 'brain', RAW, '.system_generated', 'logs')
  const cacheDir = join(agyDir, 'cache')
  mkdirSync(logsDir, { recursive: true })
  mkdirSync(cacheDir, { recursive: true })
  mkdirSync(cwd, { recursive: true })

  const transcriptPath = join(logsDir, 'transcript_full.jsonl')
  const initialSteps = [
    { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: AT, content: '<USER_REQUEST>\nhi\n</USER_REQUEST>' },
    { step_index: 1, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: AT, content: 'Before compact.', input_tokens: 4000, cache_read_tokens: 246000, output_tokens: 100 },
  ]
  writeFileSync(transcriptPath, initialSteps.map((s) => JSON.stringify(s)).join('\n') + '\n')

  const dbPath = join(agyDir, 'conversation_summaries.db')
  const db = new DatabaseSync(dbPath)
  db.exec('CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, status TEXT, not_fully_idle NUMERIC, killed NUMERIC, step_count INTEGER, title TEXT, workspace_uris TEXT, last_modified_time TEXT)')
  db.prepare('INSERT INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)').run(RAW, 'CASCADE_RUN_STATUS_IDLE', summaryStepCount, 'Compact Test', JSON.stringify([`file://${cwd}`]), AT)
  db.close()

  writeFileSync(join(cacheDir, 'conversation_metadata.json'), JSON.stringify({
    conversations: { [RAW]: { title: 'Compact Test', workspace: cwd, last_modified: AT } },
  }))

  const fakeScriptPath = join(dir, 'fake-agy.mjs')
  const fakeBin = join(dir, 'fake-agy')
  const fakeScript = `import { appendFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { DatabaseSync } from 'node:sqlite'

if (process.argv.includes('models')) { console.log('test-model\\tTest Model'); process.exit(0) }
const send = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\\n')
const rl = createInterface({ input: process.stdin })
let promptIndex = 0
const ctx = { rawId: ${JSON.stringify(RAW)}, transcriptPath: ${JSON.stringify(transcriptPath)}, dbPath: ${JSON.stringify(dbPath)}, at2: ${JSON.stringify(AT2)}, at3: ${JSON.stringify(AT3)}, appendFileSync, DatabaseSync }
rl.on('line', (line) => {
  if (!line.trim()) return
  const msg = JSON.parse(line)
  if (msg.method === 'initialize') return send({ id: msg.id, result: { protocolVersion: 1 } })
  if (msg.method === 'session/load') return send({ id: msg.id, result: { currentModeId: 'bypassPermissions' } })
  if (msg.method === 'session/prompt') {
    const idx = promptIndex++
    const text = (msg.params?.prompt || []).map((b) => b.text || '').join('\\n').trim()
    const handle = ${promptHandler.toString()}
    return handle({ msg, idx, text, send, ctx })
  }
  send({ id: msg.id, result: {} })
})
`
  writeFileSync(fakeScriptPath, fakeScript)
  writeFileSync(fakeBin, `#!/bin/sh\nexec "${process.execPath}" "${fakeScriptPath}" "$@"\n`)
  chmodSync(fakeBin, 0o755)

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home: dir,
    agyDir,
    agyBin: fakeBin,
    socketPath: node.path,
    exportPollMs: 20,
    transient401RetryDelayMs: 20,
    retryMs: 50,
  })
  runtime.start()
  t.after(async () => { await runtime.stop() })
  await node.ready
  const row = () => node.last('runtime.sessions')?.sessions?.find((s) => s.session_id === SESSION)
  await until('runtime registered', () => !!row())
  return { node, runtime, row }
}

test('/compact emits session.compaction.settled on resolve even when summary_behind keeps status unknown', async (t) => {
  const { node, row } = await setupCompactRuntime(t, {
    summaryStepCount: 1, // behind step_index 1 so transcriptState stays unknown (summary_behind)
    promptHandler: ({ msg, send }) => send({ id: msg.id, result: { stopReason: 'end_turn' } }),
  })
  assert.equal(row().status, 'unknown')
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact' })
  await until('compaction settled event emitted', () => node.all('session.compaction.settled').length === 1)
  assert.deepEqual(node.all('session.compaction.settled'), [{ session_id: SESSION }])
  await until('session list reflects summary_behind unknown status after turn ends', () => row()?.status === 'unknown')
})

test('/compact emits session.compaction.settled with error on reject and cancellation', async (t) => {
  const { node } = await setupCompactRuntime(t, {
    summaryStepCount: 1,
    promptHandler: ({ msg, idx, send }) => {
      if (idx === 0) return send({ id: msg.id, error: { code: -32000, message: 'upstream compaction failed' } })
      return send({ id: msg.id, result: { stopReason: 'cancelled' } })
    },
  })
  await node.peer.call('session.send', { session_id: SESSION, text: '/compact' })
  await until('rejection settlement emitted', () => node.all('session.compaction.settled').length === 1)
  assert.deepEqual(node.all('session.compaction.settled')[0], {
    session_id: SESSION,
    error: 'upstream compaction failed',
  })

  await node.peer.call('session.send', { session_id: SESSION, text: '/compact focus on tests' })
  await until('cancellation settlement emitted', () => node.all('session.compaction.settled').length === 2)
  assert.deepEqual(node.all('session.compaction.settled')[1], {
    session_id: SESSION,
    error: 'Compaction cancelled',
  })
})

test('non-compact prompts do not emit session.compaction.settled on resolve or reject', async (t) => {
  const { node, row } = await setupCompactRuntime(t, {
    summaryStepCount: 2,
    promptHandler: ({ msg, idx, send, ctx }) => {
      if (idx === 0) {
        const steps = [
          { step_index: 2, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: ctx.at2, content: '<USER_REQUEST>\nplease run /compact\n</USER_REQUEST>' },
          { step_index: 3, type: 'PLANNER_RESPONSE', source: 'MODEL', status: 'DONE', created_at: ctx.at3, content: 'Sure, noted.' },
        ]
        ctx.appendFileSync(ctx.transcriptPath, steps.map((s) => JSON.stringify(s)).join('\n') + '\n')
        const d = new ctx.DatabaseSync(ctx.dbPath)
        d.prepare('UPDATE conversation_summaries SET step_count = ?, last_modified_time = ? WHERE conversation_id = ?').run(4, ctx.at3, ctx.rawId)
        d.close()
        send({ method: 'session/update', params: { sessionId: msg.params.sessionId, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Sure, noted.' } } } })
        return send({ id: msg.id, result: { stopReason: 'end_turn' } })
      }
      return send({ id: msg.id, error: { code: -32000, message: 'normal prompt failed' } })
    },
  })
  await node.peer.call('session.send', { session_id: SESSION, text: 'please run /compact' })
  await until('normal turn settled', () => row()?.status === 'done' && row()?.last_text === 'Sure, noted.')
  await node.peer.call('session.send', { session_id: SESSION, text: 'trigger error' })
  await until('normal turn error surfaced', () => row()?.turn_error === 'normal prompt failed')
  assert.deepEqual(node.all('session.compaction.settled'), [])

})
