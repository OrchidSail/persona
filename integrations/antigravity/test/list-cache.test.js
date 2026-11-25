import assert from 'node:assert/strict'
import fs, { appendFileSync, mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { startFakeNode, until } from '../../runtime-sdk/testing/fake-node.js'

const IDS = [
  '11111111-1111-4111-8111-111111111111',
  '22222222-2222-4222-8222-222222222222',
  '33333333-3333-4333-8333-333333333333',
]

function line(step) {
  return JSON.stringify(step) + '\n'
}

test('settled list summaries survive transcript-cache eviction without hiding changes', { timeout: 30_000 }, async (t) => {
  const home = mkdtempSync(join(tmpdir(), 'agy-list-cache-'))
  const agyDir = join(home, 'agy')
  const brain = join(agyDir, 'brain')
  mkdirSync(brain, { recursive: true })
  const content = 'x'.repeat(33 * 1024 * 1024)
  for (const [index, id] of IDS.entries()) {
    const logs = join(brain, id, '.system_generated', 'logs')
    mkdirSync(logs, { recursive: true })
    const file = join(logs, 'transcript_full.jsonl')
    writeFileSync(file, line({
      step_index: 0,
      type: 'PLANNER_RESPONSE',
      status: 'DONE',
      created_at: '2026-11-25T00:00:00.000Z',
      content: index < 2 ? `session-${index}-` + content : 'unused-full',
    }) + (index === 0 ? line(null) : ''))
    const old = new Date(Date.now() - 60_000)
    utimesSync(file, old, old)
    if (index === 0) {
      const compact = join(logs, 'transcript.jsonl')
      writeFileSync(compact, line({
        step_index: 0,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-25T00:00:00.000Z',
        content: 'compact-race-base',
      }) + line({
        step_index: 1,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-25T00:00:01.000Z',
        content: 'compact-race',
      }))
      utimesSync(compact, old, old)
    }
    if (index === 2) {
      const compact = join(logs, 'transcript.jsonl')
      writeFileSync(compact, line({
        step_index: 0,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-25T00:00:00.000Z',
        content: 'compact-before',
      }))
      utimesSync(compact, old, old)
    }
  }
  const summaryFile = join(agyDir, 'conversation_summaries.db')
  const setStepCounts = (counts) => {
    const db = new DatabaseSync(summaryFile)
    db.exec(`CREATE TABLE IF NOT EXISTS conversation_summaries (
      conversation_id TEXT PRIMARY KEY,
      status TEXT,
      not_fully_idle NUMERIC,
      killed NUMERIC,
      step_count INTEGER,
      title TEXT,
      workspace_uris TEXT,
      last_modified_time TEXT
    )`)
    for (const [index, id] of IDS.entries()) {
      db.prepare('INSERT OR REPLACE INTO conversation_summaries VALUES (?, ?, 0, 0, ?, ?, ?, ?)')
        .run(id, 'CASCADE_RUN_STATUS_IDLE', counts[index], `Session ${index}`, '[]', '')
    }
    db.close()
  }
  setStepCounts([1, 1, 1])
  const binary = join(home, 'fake-agy.mjs')
  writeFileSync(binary, `#!/usr/bin/env node
if (process.argv.includes('models')) { console.log('test-model\\tTest Model'); process.exit(0) }
import { createInterface } from 'node:readline'
createInterface({ input: process.stdin }).on('line', (line) => {
  const request = JSON.parse(line)
  if (request.id === undefined || request.method === 'session/prompt') return
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result: {} }) + '\\n')
})
`, { mode: 0o700 })

  let transcriptReads = 0
  let transcriptBytes = 0
  let raceFile = ''
  let raceAfterStat = false
  let raceVersion = false
  const originalReadFileSync = fs.readFileSync
  const originalExistsSync = fs.existsSync
  const originalStatSync = fs.statSync
  fs.readFileSync = function (path, ...args) {
    if (String(path) === join(brain, IDS[2], '.system_generated', 'logs', 'transcript_full.jsonl')) {
      throw new Error('injected full transcript read failure')
    }
    const result = originalReadFileSync.call(this, path, ...args)
    if (String(path).endsWith('/transcript_full.jsonl')) {
      transcriptReads++
      transcriptBytes += result?.length || 0
    }
    return result
  }
  fs.existsSync = function (path, ...args) {
    if (raceVersion && String(path) === raceFile) {
      raceVersion = false
      return false
    }
    return originalExistsSync.call(this, path, ...args)
  }
  fs.statSync = function (path, ...args) {
    const result = originalStatSync.call(this, path, ...args)
    if (raceAfterStat && String(path) === raceFile) {
      raceAfterStat = false
      raceVersion = true
    }
    return result
  }
  syncBuiltinESMExports()
  t.after(() => {
    fs.readFileSync = originalReadFileSync
    fs.existsSync = originalExistsSync
    fs.statSync = originalStatSync
    syncBuiltinESMExports()
  })
  const { createAgyRuntime } = await import('../lib/runtime.js')
  const node = await startFakeNode(t)
  const runtime = createAgyRuntime({ home, agyDir, agyBin: binary, socketPath: node.path, listDays: 14, maxSessions: 3, log: () => {} })
  t.after(async () => {
    await runtime.stop()
    rmSync(home, { recursive: true, force: true })
  })
  // ctime, like mtime, must be outside the same-size rewrite detection window.
  await delay(1100)
  runtime.start()
  await node.ready
  await until('all transcript sources listed', () => {
    const rows = node.last('runtime.sessions')?.sessions
    return rows?.length === 3 && rows.every((row) => row.status === 'done' && row.last_text)
  }, 15_000)

  const refresh = async (title, predicate) => {
    const before = node.all('runtime.sessions').length
    await node.peer.call('session.rename', { session_id: `agy-${IDS[0]}`, title })
    await until('inventory refreshed', () => node.all('runtime.sessions').length > before, 15_000)
    if (predicate) await until('requested row state', predicate, 15_000)
  }

  transcriptReads = 0
  transcriptBytes = 0
  await refresh('unchanged')
  assert.equal(transcriptBytes, 0, 'an unchanged settled list must not re-read files evicted from the 64 MiB transcript cache')
  assert.equal(transcriptReads, 0)

  appendFileSync(join(brain, IDS[2], '.system_generated', 'logs', 'transcript.jsonl'), line({
    step_index: 1,
    type: 'PLANNER_RESPONSE',
    status: 'DONE',
    created_at: new Date().toISOString(),
    content: 'compact-after',
  }))
  await refresh('compact-fallback-changed', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[2]}`)
    return row?.rev === 2 && row?.last_text === 'compact-after' && row?.status === 'unknown'
  })

  setStepCounts([0, 1, 1])
  await refresh('native-state-changed', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'unknown'
  })
  setStepCounts([1, 1, 1])
  await refresh('native-state-restored', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'done'
  })

  appendFileSync(join(brain, IDS[0], '.system_generated', 'logs', 'transcript_full.jsonl'), line({
    step_index: 1,
    type: 'PLANNER_RESPONSE',
    status: 'DONE',
    created_at: '2026-11-25T00:00:01.000Z',
    content: 'after-append',
  }))
  await refresh('appended', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.rev === 2 && row?.last_text === 'after-append' && row?.status === 'unknown'
  })
  // Keep native step_count unchanged so only the transcript version can invalidate this snapshot.

  const { readTranscript, rememberObservedWake, transcriptVersion } = await import('../lib/map.js')
  const brainDir = join(brain, IDS[0])
  const fullFile = join(brainDir, '.system_generated', 'logs', 'transcript_full.jsonl')
  await delay(1100)
  setStepCounts([2, 1, 1])
  await refresh('settled-for-revision', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'done' && row?.rev === 2
  })
  const beforeRewrite = readTranscript(brainDir, true).revision
  for (let index = 0; index < 260; index++) {
    const other = join(home, `revision-${index}`)
    const logs = join(other, '.system_generated', 'logs')
    mkdirSync(logs, { recursive: true })
    writeFileSync(join(logs, 'transcript_full.jsonl'), line({
      step_index: 0,
      type: 'PLANNER_RESPONSE',
      status: 'DONE',
      created_at: '2026-11-25T00:00:00.000Z',
      content: `other-${index}`,
    }))
    readTranscript(other, true)
  }
  writeFileSync(fullFile, line({
    step_index: 0,
    type: 'PLANNER_RESPONSE',
    status: 'DONE',
    created_at: '2026-11-25T00:00:00.000Z',
    content: `session-0-` + content,
  }) + line(null) + line({
    step_index: 1,
    type: 'PLANNER_RESPONSE',
    status: 'DONE',
    created_at: '2026-11-25T00:00:01.000Z',
    content: 'after-APPEND',
  }))
  await refresh('same-step-rewrite', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'done' && row?.rev === 2 && row?.last_text === 'after-APPEND'
  })
  assert.notEqual(readTranscript(brainDir, true).revision, beforeRewrite,
    'a listed cache hit must preserve the revision record needed to detect a same-step rewrite')

  raceFile = fullFile
  raceAfterStat = true
  await refresh('full-export-selected-during-read', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'done' && row?.last_text === 'after-APPEND'
  })
  assert.equal(raceAfterStat, false, 'the fixture must reach the candidate stat before the version check')
  assert.equal(raceVersion, false, 'the fixture must make version selection fall back before the actual full read')
  rmSync(fullFile)
  await refresh('full-export-removed', () => {
    const row = node.last('runtime.sessions')?.sessions.find((row) => row.session_id === `agy-${IDS[0]}`)
    return row?.status === 'done' && row?.rev === 2 && row?.last_text === 'compact-race'
  })

  const beforeWake = transcriptVersion(brainDir, true)
  const transcript = readTranscript(brainDir, true)
  rememberObservedWake(brainDir, transcript.steps, 1)
  assert.notEqual(transcriptVersion(brainDir, true)?.key, beforeWake?.key, 'wake receipts must invalidate a listed transcript')
})
