import assert from 'node:assert/strict'
import fs, { appendFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import { DatabaseSync } from 'node:sqlite'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { startFakeNode, until } from '../../runtime-sdk/testing/fake-node.js'
import { createAgyRuntime } from '../lib/runtime.js'
import { readTranscriptAsync, stepsToItems, stepsToItemsAsync, stepsWeightRange } from '../lib/map.js'

function makeLargeTranscript(agyDir, rawId, { targetBytes = 11 * 1024 * 1024, stepCount = 260, settled = true } = {}) {
  const brainDir = join(agyDir, 'brain', rawId)
  const logs = join(brainDir, '.system_generated', 'logs')
  mkdirSync(logs, { recursive: true })
  const pairs = Math.max(1, Math.ceil(stepCount / 2))
  const padSize = Math.max(256, Math.floor(targetBytes / pairs))
  const pad = 'x'.repeat(padSize)
  const lines = []
  let idx = 0
  for (let i = 0; i < stepCount; i += 2) {
    lines.push(JSON.stringify({
      step_index: idx++,
      type: 'USER_INPUT',
      source: 'USER_EXPLICIT',
      status: 'DONE',
      created_at: `2026-11-28T00:${String(Math.floor(i / 60)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}Z`,
      content: `<USER_REQUEST>\nPrompt ${i} for ${rawId}\n</USER_REQUEST>`,
    }))
    lines.push(JSON.stringify({
      step_index: idx++,
      type: 'PLANNER_RESPONSE',
      status: 'DONE',
      created_at: `2026-11-28T00:${String(Math.floor((i + 1) / 60)).padStart(2, '0')}:${String((i + 1) % 60).padStart(2, '0')}Z`,
      content: `Reply ${i} for ${rawId} ${pad}`,
    }))
  }
  const file = join(logs, 'transcript_full.jsonl')
  writeFileSync(file, lines.join('\n') + '\n')
  if (settled) {
    const past = new Date(Date.now() - 10_000)
    utimesSync(file, past, past)
  }
  return { brainDir, file, stepCount: idx }
}

test('watched >10MB session survives cold listSessions scans over >64MiB, reuses snapshot in session.item, and unprotects on unwatch', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-cache-stall-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const agyDir = join(home, '.gemini', 'antigravity-cli')

  const hotId = '11111111-1111-4111-8111-111111111111'
  const hot = makeLargeTranscript(agyDir, hotId, { targetBytes: 11 * 1024 * 1024, stepCount: 220 })
  const coldIds = [
    '22222222-2222-4222-8222-222222222221',
    '22222222-2222-4222-8222-222222222222',
    '22222222-2222-4222-8222-222222222223',
    '22222222-2222-4222-8222-222222222224',
    '22222222-2222-4222-8222-222222222225',
    '22222222-2222-4222-8222-222222222226',
  ]

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({ agyBin: join(home, 'missing-agy'), agyDir, home, socketPath: node.path, log: () => {} })
  t.after(() => runtime.stop())
  runtime.start()
  await node.ready

  const hotSid = `agy-${hotId}`
  await node.peer.call('session.watch', { session_id: hotSid })
  const warmPage = await node.peer.call('session.items', { session_id: hotSid, limit: 20 })
  assert.equal(warmPage.items.length, 20)
  const hotBefore = await readTranscriptAsync(hot.brainDir, true)
  assert.equal(hotBefore.steps.length, hot.stepCount)

  // Now create 6 cold >11MB transcripts (>66MB total, exceeding 64MiB cache budget) and trigger listSessions scan
  for (const cid of coldIds) {
    makeLargeTranscript(agyDir, cid, { targetBytes: 11 * 1024 * 1024, stepCount: 220 })
  }
  const beforeList = node.all('runtime.sessions').length
  await node.peer.call('session.rename', { session_id: hotSid, title: 'Hot Session' })
  await until('cold listSessions scanned all 7 sessions', () => node.all('runtime.sessions').length > beforeList && (node.last('runtime.sessions')?.sessions?.length ?? 0) >= 1 + coldIds.length, 15_000)

  // Watched hot session must remain in transcriptCache (exact same cached steps reference, no cold reread)
  const hotAfter = await readTranscriptAsync(hot.brainDir, true)
  assert.ok(hotAfter.steps === hotBefore.steps, 'watched hot transcript must not be evicted by cold listSessions scan')

  // Warm items() and non-truncated session.item() should serve consistent results from cache
  const warmPage2 = await node.peer.call('session.items', { session_id: hotSid, limit: 20 })
  assert.deepEqual(warmPage2.items.map((it) => it.id), warmPage.items.map((it) => it.id))
  const userItem = warmPage2.items.find((it) => it.role === 'user' && !it.truncated)
  assert.ok(userItem, 'expected a non-truncated user item')
  const fetchedItem = await node.peer.call('session.item', { session_id: hotSid, item_id: userItem.id })
  assert.deepEqual(fetchedItem, userItem)

  // Unwatch releases protection so cache remains strictly bounded under >64MiB of new watched sessions
  await node.peer.call('session.unwatch', { session_id: hotSid })
  for (const cid of coldIds) {
    await node.peer.call('session.watch', { session_id: `agy-${cid}` })
    await node.peer.call('session.items', { session_id: `agy-${cid}`, limit: 5 })
  }
  const hotEvicted = await readTranscriptAsync(hot.brainDir, true)
  assert.ok(hotEvicted.steps !== hotBefore.steps, 'unwatched session should be evictable once >64MiB of other watched sessions fill the bounded cache')
  for (const cid of coldIds) {
    await node.peer.call('session.unwatch', { session_id: `agy-${cid}` })
  }
})

test('cold >12MB transcript read and mapping yields event loop so concurrent session.unwatch is not head-of-line blocked', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-el-yield-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const agyDir = join(home, '.gemini', 'antigravity-cli')

  const watchId = '33333333-3333-4333-8333-333333333331'
  const coldId = '33333333-3333-4333-8333-333333333332'
  makeLargeTranscript(agyDir, watchId, { targetBytes: 64 * 1024, stepCount: 10 })
  const cold = makeLargeTranscript(agyDir, coldId, { targetBytes: 14 * 1024 * 1024, stepCount: 2400 })

  // Verify stepsToItemsAsync matches sync stepsToItems identically
  const coldTr = await readTranscriptAsync(cold.brainDir, true, { cold: true })
  const syncRes = stepsToItems(coldTr.steps, { brainDir: cold.brainDir, full: false, includeMedia: true, skipCache: true })
  const asyncRes = await stepsToItemsAsync(coldTr.steps, { brainDir: cold.brainDir, full: false, includeMedia: true, skipCache: true })
  assert.equal(asyncRes.items.length, syncRes.items.length)
  assert.equal(asyncRes.toSeq, syncRes.toSeq)

  // Fresh cold session not yet in cache for runtime RPC test
  const coldId2 = '33333333-3333-4333-8333-333333333333'
  makeLargeTranscript(agyDir, coldId2, { targetBytes: 14 * 1024 * 1024, stepCount: 2400 })

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({ agyBin: join(home, 'missing-agy'), agyDir, home, socketPath: node.path, log: () => {} })
  t.after(() => runtime.stop())
  runtime.start()
  await node.ready

  const watchSid = `agy-${watchId}`
  const coldSid2 = `agy-${coldId2}`
  await node.peer.call('session.watch', { session_id: watchSid })

  let ticksDuringCold = 0
  let coldDone = false
  const ticker = () => {
    if (coldDone) return
    ticksDuringCold++
    setImmediate(ticker)
  }
  setImmediate(ticker)

  let unwatchFinishedBeforeCold = false
  const coldItemsPromise = node.peer.call('session.items', { session_id: coldSid2, limit: 20 }).then((res) => {
    coldDone = true
    return res
  })
  const coldItemsCoalesced = node.peer.call('session.items', { session_id: coldSid2, limit: 10 })
  const unwatchPromise = node.peer.call('session.unwatch', { session_id: watchSid }).then((res) => {
    unwatchFinishedBeforeCold = !coldDone
    return res
  })

  const [itemsRes, coalescedRes, unwatchRes] = await Promise.all([coldItemsPromise, coldItemsCoalesced, unwatchPromise])
  assert.equal(unwatchRes.ok, true)
  assert.equal(itemsRes.items.length, 20)
  assert.equal(coalescedRes.items.length, 10)
  assert.equal(unwatchFinishedBeforeCold, true, 'session.unwatch must complete before cold >12MB session.items finishes')
  assert.ok(ticksDuringCold >= 2, `expected event loop to yield during cold >12MB load (saw ${ticksDuringCold} ticks)`)
})

test('itemsCache does not double-count stepWeight so >16MB transcripts with high stepWeight + reasonable itemWeight hit cache and preserve invalidation', async () => {
  // Build a >16MB-equivalent transcript whose stepWeight alone exceeds 48MiB (via large clipped thinking
  // + content fields) while retained itemWeight stays around ~20MiB (< 64MiB MAX_ITEMS_CACHE_BYTES).
  // Previously snapshot.bytes = stepWeight (~56MiB) + itemWeight (~20MiB) = ~76MiB > 64MiB caused 100% cache MISS.
  const largeClippedThinking = 't'.repeat(160_000) // 320KB UTF-16 in stepWeight, clipped to ~24KB in itemWeight
  const moderateReply = 'r'.repeat(8_000) // 16KB UTF-16 in both stepWeight and itemWeight
  const steps = []
  for (let i = 0; i < 180; i++) {
    steps.push({
      step_index: i,
      source: 'MODEL',
      type: 'PLANNER_RESPONSE',
      status: 'DONE',
      created_at: `2026-11-29T00:${String(Math.floor(i / 60)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}Z`,
      thinking: largeClippedThinking,
      content: `Reply ${i}: ${moderateReply}`,
    })
  }
  const rawStepWeight = stepsWeightRange(steps)
  assert.ok(rawStepWeight > 55 * 1024 * 1024, `expected stepWeight > 55MiB, got ${rawStepWeight}`)

  const cacheSlot = 'large-double-count-test|0|0'
  const cacheKey = `${cacheSlot}|h1`
  const r1 = await stepsToItemsAsync(steps, { full: false, cacheSlot, cacheKey })
  const t0 = performance.now()
  const r2 = await stepsToItemsAsync(steps, { full: false, cacheSlot, cacheKey })
  const hitMs = performance.now() - t0
  assert.equal(r2, r1, 'second call on >16MB-equivalent transcript must hit itemsCache instead of silently missing')
  assert.ok(hitMs < 50, `expected sub-50ms cache hit, took ${hitMs.toFixed(1)}ms`)

  // Replacing a single step object (even with identical fields) changes its WeakMap identity and must NOT return stale cache.
  const replaced = steps.slice()
  replaced[10] = { ...steps[10], content: 'Modified step 10' }
  const r3 = await stepsToItemsAsync(replaced, { full: false, cacheSlot, cacheKey })
  assert.notEqual(r3, r1, 'replacing a step object must invalidate itemsCache')
  assert.ok(r3.items.some((it) => it.text === 'Modified step 10'))

  // Prefix reuse with mutable live tail still reuses immutable prefix items and never serves stale in-place mutations.
  const liveStep = {
    step_index: 180,
    source: 'MODEL',
    type: 'PLANNER_RESPONSE',
    status: 'RUNNING',
    created_at: '2026-11-29T00:05:00Z',
    content: 'live-v1',
  }
  const rBase = stepsToItems(steps, { full: false, cacheSlot, cacheKey, immutablePrefix: steps.length })
  const rLive1 = stepsToItems([...steps, liveStep], { full: false, cacheSlot, cacheKey, immutablePrefix: steps.length })
  assert.equal(rLive1.items[0], rBase.items[0], 'immutable prefix item should be reused')
  assert.equal(rLive1.items.at(-1).text, 'live-v1')
  liveStep.content = 'live-v2'
  const rLive2 = stepsToItems([...steps, liveStep], { full: false, cacheSlot, cacheKey, immutablePrefix: steps.length })
  assert.equal(rLive2.items[0], rBase.items[0], 'immutable prefix item should stay reused across live updates')
  assert.equal(rLive2.items.at(-1).text, 'live-v2', 'in-place live tail mutation must never serve stale cached text')
})

test('prepareTranscript does not block session.items/item/cursor on hanging GetCascadeTrajectory and backs off exponentially on repeated timeouts', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-cut-hang-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const config = join(home, 'config')
  const rawId = '55555555-6666-4777-8888-999999999999'
  const sid = `agy-${rawId}`
  const logs = join(config, 'brain', rawId, '.system_generated', 'logs')
  mkdirSync(logs, { recursive: true })
  const baseSteps = [
    { step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: '2026-11-29T00:00:00Z', content: 'Q1' },
    { step_index: 1, type: 'PLANNER_RESPONSE', status: 'DONE', created_at: '2026-11-29T00:00:01Z', content: 'A1' },
    { step_index: 2, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: '2026-11-29T00:00:02Z', content: 'Q2' },
    { step_index: 3, type: 'PLANNER_RESPONSE', status: 'DONE', created_at: '2026-11-29T00:00:03Z', content: 'A2' },
  ]
  writeFileSync(join(logs, 'transcript_full.jsonl'), baseSteps.map((s) => JSON.stringify(s)).join('\n') + '\n')

  const db = new DatabaseSync(join(config, 'conversation_summaries.db'))
  t.after(() => db.close())
  db.exec('CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, status TEXT, not_fully_idle INTEGER, killed INTEGER, step_count INTEGER, title TEXT, workspace_uris TEXT, last_modified_time TEXT)')
  db.prepare('INSERT INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)').run(
    rawId, 'CASCADE_RUN_STATUS_IDLE', 2, 'Hanging trajectory session', JSON.stringify([`file://${home}`]), '2026-11-29T00:00:10Z',
  )

  const callsFile = join(home, 'calls.jsonl')
  writeFileSync(callsFile, '')
  const modeFile = join(home, 'mode.json')
  writeFileSync(modeFile, JSON.stringify({ mode: 'hang' }))
  const releaseFile = join(home, 'release-cut')

  const fakeAcp = join(home, 'fake-acp-hang.mjs')
  writeFileSync(fakeAcp, `
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { createInterface } from 'node:readline'
if (process.argv.includes('models')) { console.log('test-model\\tTest Model'); process.exit(0) }
const dir = process.argv[2]
const logFile = process.argv[process.argv.indexOf('--log-file') + 1]
const callsFile = dir + '/calls.jsonl'
const send = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\\n')
const server = createServer(async (req, res) => {
  const chunks = []
  for await (const c of req) chunks.push(c)
  const method = req.url.split('/').at(-1)
  appendFileSync(callsFile, JSON.stringify({ method, at: Date.now() }) + '\\n')
  if (method === 'GetCascadeTrajectory') {
    const cfg = JSON.parse(readFileSync(dir + '/mode.json', 'utf8'))
    if (cfg.mode === 'hang') {
      while (!existsSync(dir + '/release-cut')) await new Promise((r) => setTimeout(r, 20))
      const after = JSON.parse(readFileSync(dir + '/mode.json', 'utf8'))
      if (after.mode === 'fail') {
        res.writeHead(504).end(JSON.stringify({ message: 'Antigravity conversation operation timed out' }))
        return
      }
      res.end(JSON.stringify(after.response))
      return
    }
    if (cfg.mode === 'fail') {
      res.writeHead(504).end(JSON.stringify({ message: 'Antigravity conversation operation timed out' }))
      return
    }
    res.end(JSON.stringify(cfg.response))
    return
  }
  res.writeHead(404).end(JSON.stringify({ message: 'Unknown method' }))
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
writeFileSync(logFile, 'Language server listening on random port at ' + server.address().port + ' for HTTP\\n')
const rl = createInterface({ input: process.stdin })
rl.on('line', (line) => {
  const req = JSON.parse(line)
  if (req.id === undefined) return
  send({ id: req.id, result: {} })
})
rl.on('close', () => server.close(() => process.exit(0)))
`)

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home,
    agyDir: config,
    agyBin: process.execPath,
    agyArgs: [fakeAcp, home],
    socketPath: node.path,
    cutWaitMs: 80,
    cutRetryMs: 120,
    cutRetryMaxMs: 600,
    log: () => {},
  })
  t.after(() => runtime.stop())
  runtime.start()
  await node.ready
  await until('session listed', () => node.last('runtime.sessions')?.sessions?.some((s) => s.session_id === sid))

  const trajectoryCalls = () => readFileSync(callsFile, 'utf8').split('\n').filter(Boolean).map(JSON.parse).filter((c) => c.method === 'GetCascadeTrajectory')

  // 1. Hanging GetCascadeTrajectory must NOT block session.items, session.item, or session.cursor.
  const t1 = performance.now()
  const page1 = await node.peer.call('session.items', { session_id: sid })
  const dur1 = performance.now() - t1
  assert.equal(page1.items.filter((it) => it.kind === 'text').length, 4, 'fallback returns local transcript')
  assert.ok(dur1 < 1200, `first session.items should degrade after cutWaitMs, took ${dur1.toFixed(1)}ms`)

  await until('background GetCascadeTrajectory started', () => trajectoryCalls().length === 1)

  // Second call while cutFlight is still hanging must return immediately without spawning another RPC
  // (session.watch invokes cursor() -> prepareTranscript()).
  const t2 = performance.now()
  const [page2, watched, fetched] = await Promise.all([
    node.peer.call('session.items', { session_id: sid }),
    node.peer.call('session.watch', { session_id: sid }),
    node.peer.call('session.item', { session_id: sid, item_id: page1.items[0].id }),
  ])
  const dur2 = performance.now() - t2
  assert.equal(page2.to_seq, 4)
  assert.equal(watched.ok, true)
  assert.equal(fetched.id, page1.items[0].id)
  assert.ok(dur2 < 300, `subsequent reads during in-flight cut check must not re-wait (took ${dur2.toFixed(1)}ms)`)
  assert.equal(trajectoryCalls().length, 1, 'in-flight cut check must coalesce without duplicate RPCs')
  await node.peer.call('session.unwatch', { session_id: sid })

  // Release hanging check as a timeout failure -> fail #1 backs off 120ms (cutRetryMs).
  writeFileSync(modeFile, JSON.stringify({ mode: 'fail' }))
  writeFileSync(releaseFile, '')
  await new Promise((r) => setTimeout(r, 60))

  // Inside 120ms backoff window -> must not retry.
  await node.peer.call('session.items', { session_id: sid })
  assert.equal(trajectoryCalls().length, 1, 'must stay backed off during first 120ms retry window')

  // Past 120ms -> retry #2 fires and fails -> exponential backoff grows to 120 * 4 = 480ms.
  await new Promise((r) => setTimeout(r, 140))
  await node.peer.call('session.items', { session_id: sid })
  await until('second GetCascadeTrajectory attempt recorded', () => trajectoryCalls().length === 2)

  // At +180ms (past flat 120ms, but well inside 480ms exponential backoff) -> must NOT retry!
  await new Promise((r) => setTimeout(r, 180))
  await node.peer.call('session.items', { session_id: sid })
  assert.equal(trajectoryCalls().length, 2, 'exponential backoff (480ms) must prevent retrying at +180ms')

  // Past 480ms -> retry #3 fires cleanly.
  await new Promise((r) => setTimeout(r, 360))
  await node.peer.call('session.items', { session_id: sid })
  await until('third GetCascadeTrajectory attempt recorded after exponential backoff', () => trajectoryCalls().length === 3)
})

test('watchedPolls evaluates active large transcript appends asynchronously without blocking event loop or using sync transcript reads', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-async-poll-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const agyDir = join(home, 'agy')
  const rawId = '66666666-7777-4888-8999-aaaaaaaaaaaa'
  const sid = `agy-${rawId}`
  const large = makeLargeTranscript(agyDir, rawId, { targetBytes: 12 * 1024 * 1024, stepCount: 240, settled: true })

  // Mark nativeWorking in SQLite so appendPollStamp returns null (active overlay path).
  const db = new DatabaseSync(join(agyDir, 'conversation_summaries.db'))
  t.after(() => db.close())
  db.exec('CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, status TEXT, not_fully_idle INTEGER, killed INTEGER, step_count INTEGER, title TEXT, workspace_uris TEXT, last_modified_time TEXT)')
  db.prepare('INSERT INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)').run(
    rawId, 'CASCADE_RUN_STATUS_RUNNING', large.stepCount, 'Active large session', '[]', '2026-11-29T00:00:00Z',
  )

  let pendingTimer = null
  const watchTimer = {
    set(fn) { pendingTimer = fn; return 1 },
    clear() { pendingTimer = null },
  }

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home,
    agyDir,
    agyBin: join(home, 'missing-agy'),
    socketPath: node.path,
    watchPollMs: 400,
    watchTimer,
    log: () => {},
  })
  t.after(() => runtime.stop())
  runtime.start()
  await node.ready

  await node.peer.call('session.watch', { session_id: sid, since_seq: large.stepCount })
  assert.ok(pendingTimer, 'watch must schedule a poll tick')

  // Spy on sync reads targeting the large transcript file to ensure watchedPolls never falls back to sync readTranscript.
  let syncTranscriptReads = 0
  const origReadFileSync = fs.readFileSync
  const origOpenSync = fs.openSync
  fs.readFileSync = function (p, ...rest) {
    if (typeof p === 'string' && p === large.file) syncTranscriptReads++
    return origReadFileSync.call(this, p, ...rest)
  }
  fs.openSync = function (p, ...rest) {
    if (typeof p === 'string' && p === large.file) syncTranscriptReads++
    return origOpenSync.call(this, p, ...rest)
  }
  syncBuiltinESMExports()
  t.after(() => {
    fs.readFileSync = origReadFileSync
    fs.openSync = origOpenSync
    syncBuiltinESMExports()
  })

  // Append a new reply step while transcript is cold/unmapped in watched poll.
  const appended = {
    step_index: large.stepCount,
    type: 'PLANNER_RESPONSE',
    status: 'DONE',
    created_at: '2026-11-29T00:10:00Z',
    content: 'Async watched poll reply',
  }
  appendFileSync(large.file, JSON.stringify(appended) + '\n')

  let loopTicks = 0
  let pollDone = false
  const pump = () => { if (!pollDone) { loopTicks++; setImmediate(pump) } }
  setImmediate(pump)

  const tick = pendingTimer
  pendingTimer = null
  await tick()
  pollDone = true

  assert.equal(syncTranscriptReads, 0, 'watchedPolls must use async readTranscriptAsync rather than sync readFileSync/openSync')
  assert.ok(loopTicks >= 2, `expected event loop yields during async watched poll on >12MB transcript (saw ${loopTicks})`)
  assert.ok(pendingTimer, 'watchedPolls must reschedule next tick after async evaluation')
  await until('appended item pushed to watched client', () =>
    node.all('session.items').some((b) => b.session_id === sid && b.items?.some((it) => it.text === 'Async watched poll reply')),
  )
})

test('listSummaryBySession enforces tightened default cap (32 entries) and stores lightweight stepIds instead of retaining full steps weight', async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-summary-cap-'))
  t.after(() => rmSync(home, { recursive: true, force: true }))
  const agyDir = join(home, 'agy')
  const baseTime = Date.now() - 120_000

  // Create 38 sessions (exceeding tightened default maxListSummaryEntries=32), including one multi-MB session.
  for (let i = 0; i < 38; i++) {
    const id = `77777777-7777-4777-8777-${String(i + 1).padStart(12, '0')}`
    const logs = join(agyDir, 'brain', id, '.system_generated', 'logs')
    mkdirSync(logs, { recursive: true })
    const file = join(logs, 'transcript_full.jsonl')
    const pad = i === 37 ? 'z'.repeat(2 * 1024 * 1024) : 's'.repeat(4_000)
    writeFileSync(file, [
      JSON.stringify({ step_index: 0, type: 'USER_INPUT', source: 'USER_EXPLICIT', status: 'DONE', created_at: '2026-11-29T00:00:00Z', content: `Prompt ${i}` }),
      JSON.stringify({ step_index: 1, type: 'PLANNER_RESPONSE', status: 'DONE', created_at: '2026-11-29T00:00:01Z', content: `Reply ${i} ${pad}` }),
    ].join('\n') + '\n')
    const ts = new Date(baseTime + i * 1000)
    utimesSync(file, ts, ts)
  }

  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({
    home,
    agyDir,
    agyBin: join(home, 'missing-agy'),
    socketPath: node.path,
    log: () => {},
  })
  t.after(() => runtime.stop())
  runtime.start()
  await node.ready

  await until('all 38 sessions listed', () => (node.last('runtime.sessions')?.sessions?.length ?? 0) === 38, 15_000)
  const stats = runtime._debugStats()
  assert.ok(stats.listSummaryCount <= 32, `listSummaryBySession count ${stats.listSummaryCount} exceeded tightened cap of 32`)
  assert.equal(stats.listSummaryCount, 32, 'expected LRU cache to fill up to the 32-entry cap')
  // Even with a 2MB+ transcript included, listSummaryBytes must stay lightweight (well under 100KB, not MBs of retained steps).
  assert.ok(stats.listSummaryBytes < 100 * 1024, `expected lightweight stepIds/summary weight (<100KB), got ${stats.listSummaryBytes}`)
})
