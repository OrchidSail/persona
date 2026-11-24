package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
	"github.com/nwverse/persona/server/internal/transcript"
)

func newMainAutoCompactTestFixture(t *testing.T, mainID string) (*Service, *fakeHub, *time.Time, func(time.Duration)) {
	t.Helper()
	f := newFakeHub().withMachine()
	s := newTestService(t, f)
	now := time.Date(2026, 11, 24, 12, 0, 0, 0, time.UTC)
	var clockMu sync.Mutex
	s.mainAutoCompact.now = func() time.Time {
		clockMu.Lock()
		defer clockMu.Unlock()
		return now
	}
	advance := func(d time.Duration) {
		clockMu.Lock()
		now = now.Add(d)
		clockMu.Unlock()
	}
	setSettings(t, s, func(v *Settings) {
		v.MachineID = "server"
		v.Main = mainID
		v.MainMachineID = "server"
	})
	return s, f, &now, advance
}

func TestMainAutoCompactTokenThresholdMath(t *testing.T) {
	for _, tc := range []struct {
		name   string
		cfg    Settings
		window int
		want   int
	}{
		{"default 400k caps 1M Gemini window", Settings{}, 1_048_576, 300_000},
		{"default 400k respects smaller 200k window", Settings{}, 200_000, 150_000},
		{"default 400k respects 131k window", Settings{}, 131_072, 98_304},
		{"default 400k applies when model window unknown", Settings{}, 0, 300_000},
		{"main_compact_window=0 follows 1M model window", Settings{MainCompactWindow: ptr(0)}, 1_048_576, 786_432},
		{"main_compact_window=0 follows 200k model window", Settings{MainCompactWindow: ptr(0)}, 200_000, 150_000},
		{"main_compact_window=0 with unknown model window", Settings{MainCompactWindow: ptr(0)}, 0, 0},
		{"custom main_compact_window=240k on 1M model", Settings{MainCompactWindow: ptr(240_000)}, 1_048_576, 180_000},
		{"custom main_compact_window=240k on 200k model", Settings{MainCompactWindow: ptr(240_000)}, 200_000, 150_000},
		{"saved idle_auto_compact_tokens overrides window ratio", Settings{IdleAutoCompactTokens: ptr(210_000)}, 1_048_576, 210_000},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := mainAutoCompactTokenThreshold(tc.cfg, tc.window); got != tc.want {
				t.Fatalf("mainAutoCompactTokenThreshold(%+v, %d) = %d, want %d", tc.cfg, tc.window, got, tc.want)
			}
		})
	}
	if !plausibleMainContext(1_020_000, 1_048_576) {
		t.Fatal("1.02M tokens on a 1,048,576 Gemini window must be plausible")
	}
	if plausibleMainContext(1_700_000, 1_048_576) || plausibleMainContext(1_200_000, 0) {
		t.Fatal("impossible gateway token spike must be rejected")
	}
}

func TestMainAutoCompactTriggersOnIdleTurnEndForAgyAndHonorsSettings(t *testing.T) {
	ctx := context.Background()
	mainID := "agy-11111111-2222-3333-4444-555555555555"
	s, f, _, advance := newMainAutoCompactTestFixture(t, mainID)

	var compactCalls []map[string]any
	var callsMu sync.Mutex
	f.answerNode(DeviceAssistantCompact, func(_ string, raw json.RawMessage) (any, error) {
		var p map[string]any
		_ = json.Unmarshal(raw, &p)
		callsMu.Lock()
		compactCalls = append(compactCalls, p)
		callsMu.Unlock()
		return map[string]any{"ok": true}, nil
	})
	countCompacts := func() int {
		callsMu.Lock()
		defer callsMu.Unlock()
		return len(compactCalls)
	}

	// Below default 300k threshold (75% of 400k): no compaction.
	f.setSessions("server", proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 20, ContextTokens: 250_000, ContextWindow: 1_048_576,
	})
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if countCompacts() != 0 {
		t.Fatalf("compacted below threshold: %d", countCompacts())
	}

	// Side chat above threshold: never auto-compacted.
	sideID := "agy-99999999-2222-3333-4444-555555555555"
	f.setSessions("server",
		proto.Session{SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy", Status: proto.StatusDone, Rev: 20, ContextTokens: 250_000, ContextWindow: 1_048_576},
		proto.Session{SessionID: sideID, Assistant: true, Agent: "agy", Launcher: "agy", Status: proto.StatusDone, Rev: 40, ContextTokens: 350_000, ContextWindow: 1_048_576},
	)
	s.mainAutoCompactTurnSettled(ctx, "server", sideID)
	if countCompacts() != 0 {
		t.Fatalf("side conversation was compacted: %d", countCompacts())
	}

	// Explicit opt-out via assistant.idle_auto_compact = false skips compaction even over threshold.
	f.setSessions("server", proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 25, ContextTokens: 320_000, ContextWindow: 1_048_576,
	})
	setSettings(t, s, func(v *Settings) { v.IdleAutoCompact = ptr(false) })
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if countCompacts() != 0 {
		t.Fatalf("compacted while idle_auto_compact=false: %d", countCompacts())
	}

	// Re-enable and cross 300k threshold through the real TurnEnded -> afterSettle path.
	setSettings(t, s, func(v *Settings) { v.IdleAutoCompact = nil })
	s.TurnEnded(ctx, SessionRef{MachineID: "server", SessionID: mainID})
	waitFor(t, "main agy session auto-compacted", func() bool { return countCompacts() == 1 })
	callsMu.Lock()
	gotParams := compactCalls[0]
	callsMu.Unlock()
	if gotParams["session_id"] != mainID {
		t.Fatalf("compact session_id = %v, want %s", gotParams["session_id"], mainID)
	}
	if _, hasOnlyRunning := gotParams["only_running"]; hasOnlyRunning {
		t.Fatalf("runtime-compatible auto-compact must not pass only_running: %v", gotParams)
	}

	// Complete the compaction and advance past cooldown.
	started := s.mainAutoCompact.clock()
	s.SessionsChanged(ctx, DeviceSessions{
		MachineID: "server",
		Sessions: []proto.Session{{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 26, ContextTokens: 0, ContextWindow: 1_048_576,
			Compaction: &proto.Compaction{State: proto.CompactionDone, StartedAt: started},
		}},
	})
	advance(defaultMainAutoCompactCooldown + time.Minute)

	// With main_compact_window = 0 (follow 1M model window -> 786,432 threshold), 350k does not trigger; 800k does.
	setSettings(t, s, func(v *Settings) { v.MainCompactWindow = ptr(0) })
	f.setSessions("server", proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 30, ContextTokens: 350_000, ContextWindow: 1_048_576,
	})
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if countCompacts() != 1 {
		t.Fatalf("compacted at 350k with main_compact_window=0 on 1M model: %d", countCompacts())
	}
	f.setSessions("server", proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 35, ContextTokens: 800_000, ContextWindow: 1_048_576,
	})
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if countCompacts() != 2 {
		t.Fatalf("did not compact at 800k on 1M model: %d", countCompacts())
	}
}

func TestMainAutoCompactFallbackStepsBytesAndCompactionBoundary(t *testing.T) {
	ctx := context.Background()

	t.Run("unreported tokens triggers on incremental item volume bytes", func(t *testing.T) {
		mainID := "agy-22222222-2222-3333-4444-555555555555"
		s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
		var compacts atomic.Int32
		f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
			compacts.Add(1)
			return map[string]any{"ok": true}, nil
		})
		f.setItems(
			proto.Item{Seq: 1, Role: "user", Kind: proto.KindText, Text: "Analyze logs"},
			proto.Item{Seq: 10, Role: "user", Kind: proto.KindToolResult, Text: "clipped", FullSize: 300 * 1024},
			proto.Item{Seq: 24, Role: "user", Kind: proto.KindToolResult, Text: "clipped", FullSize: 280 * 1024},
		)
		f.setSessions("server", proto.Session{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 25, ContextTokens: 0, ContextWindow: 1_048_576,
		})
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		if compacts.Load() != 1 {
			t.Fatalf("compacts = %d, want 1 via fallback byte volume", compacts.Load())
		}
	})

	t.Run("recent compaction notice in items resets baseline and prevents false trigger", func(t *testing.T) {
		mainID := "agy-33333333-2222-3333-4444-555555555555"
		s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
		var compacts atomic.Int32
		f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
			compacts.Add(1)
			return map[string]any{"ok": true}, nil
		})
		// High historical Rev and OutputTokens after restart, but CHECKPOINT notice landed at seq 390.
		f.setItems(
			proto.Item{Seq: 300, Role: "user", Kind: proto.KindToolResult, Text: "old huge output", FullSize: 900 * 1024},
			proto.Item{Seq: 390, Role: "system", Kind: proto.KindNotice, Text: transcript.CompactedNotice},
			proto.Item{Seq: 395, Role: "user", Kind: proto.KindText, Text: "Short follow-up"},
			proto.Item{Seq: 400, Role: "assistant", Kind: proto.KindText, Text: "Short reply"},
		)
		f.setSessions("server", proto.Session{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 400, ContextTokens: 0, OutputTokens: 120_000,
		})
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		if compacts.Load() != 0 {
			t.Fatalf("compacts = %d, want 0 because recent compaction notice was at seq 390", compacts.Load())
		}
	})

	t.Run("unreported tokens triggers on incremental steps", func(t *testing.T) {
		mainID := "agy-44444444-2222-3333-4444-555555555555"
		s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
		var compacts atomic.Int32
		f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
			compacts.Add(1)
			return map[string]any{"ok": true}, nil
		})
		f.setItems(
			proto.Item{Seq: 1, Role: "user", Kind: proto.KindText, Text: "Start"},
			proto.Item{Seq: 170, Role: "assistant", Kind: proto.KindText, Text: "Latest"},
		)
		f.setSessions("server", proto.Session{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 170, ContextTokens: 0,
		})
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		if compacts.Load() != 1 {
			t.Fatalf("compacts = %d, want 1 via fallback step count", compacts.Load())
		}
	})

	t.Run("stale frozen tokens fall back to step count while fresh tokens stay authoritative", func(t *testing.T) {
		mainID := "agy-55555555-2222-3333-4444-555555555555"
		s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
		var compacts atomic.Int32
		f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
			compacts.Add(1)
			return map[string]any{"ok": true}, nil
		})
		// Fresh token update at Rev 180 (120k < 300k): must NOT falsely trigger fallback.
		row := proto.Session{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 10, ContextTokens: 100_000, ContextWindow: 1_048_576,
		}
		s.SessionsChanged(ctx, DeviceSessions{MachineID: "server", Sessions: []proto.Session{row}})
		row.Rev, row.ContextTokens = 180, 120_000
		f.setSessions("server", row)
		s.SessionsChanged(ctx, DeviceSessions{MachineID: "server", Sessions: []proto.Session{row}})
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		if compacts.Load() != 0 {
			t.Fatalf("fresh below-threshold tokens falsely triggered fallback: %d", compacts.Load())
		}

		// Now tokens stay frozen at 120k while Rev grows by 170 more steps -> stale fallback triggers.
		row.Rev = 350
		f.setSessions("server", row)
		f.setItems(proto.Item{Seq: 350, Role: "assistant", Kind: proto.KindText, Text: "Still frozen tokens"})
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		if compacts.Load() != 1 {
			t.Fatalf("stale frozen tokens failed to trigger fallback: %d", compacts.Load())
		}
	})
}

func TestMainAutoCompactDelayedExportAfterTurnEnd(t *testing.T) {
	ctx := context.Background()
	mainID := "agy-66666666-2222-3333-4444-555555555555"
	s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
	var compacts atomic.Int32
	f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
		compacts.Add(1)
		return map[string]any{"ok": true}, nil
	})

	// Turn ends before transcript_full.jsonl flushes: pre-turn tokens are still below threshold.
	stale := proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 40, ContextTokens: 260_000, ContextWindow: 1_048_576,
	}
	f.setSessions("server", stale)
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 0 {
		t.Fatalf("compacted before delayed usage arrived: %d", compacts.Load())
	}

	// Delayed export arrives while still idle with updated context_tokens over threshold.
	flushed := stale
	flushed.Rev = 44
	flushed.ContextTokens = 325_000
	f.setSessions("server", flushed)
	s.SessionsChanged(ctx, DeviceSessions{MachineID: "server", Sessions: []proto.Session{flushed}})
	waitFor(t, "delayed export triggered auto-compaction", func() bool { return compacts.Load() == 1 })
}

func TestMainAutoCompactBusyAndActiveGuards(t *testing.T) {
	ctx := context.Background()
	mainID := "agy-77777777-2222-3333-4444-555555555555"

	base := proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 40, ContextTokens: 360_000, ContextWindow: 1_048_576,
	}
	undelivered := []proto.UndeliveredMessage{{ID: "u1", Text: "pending"}}

	cases := []struct {
		name   string
		mutate func(*Service, *proto.Session)
	}{
		{"working", func(_ *Service, s *proto.Session) { s.Status = proto.StatusWorking }},
		{"blocked", func(_ *Service, s *proto.Session) { s.Status = proto.StatusBlocked }},
		{"offline", func(_ *Service, s *proto.Session) { s.Status = proto.StatusOffline }},
		{"starting", func(_ *Service, s *proto.Session) { s.Starting = true }},
		{"background working", func(_ *Service, s *proto.Session) { s.BackgroundWorking = true }},
		{"background waiting", func(_ *Service, s *proto.Session) { s.BackgroundWaiting = true }},
		{"background task", func(_ *Service, s *proto.Session) { s.Background = []proto.BackgroundTask{{Kind: "shell"}} }},
		{"queued on node", func(_ *Service, s *proto.Session) { s.Queued = []string{"next"} }},
		{"undelivered owner message", func(_ *Service, s *proto.Session) { s.Undelivered = &undelivered }},
		{"already compacting", func(_ *Service, s *proto.Session) {
			s.Compaction = &proto.Compaction{State: proto.CompactionRunning, StartedAt: time.Now().UTC()}
		}},
		{"needs you state", func(_ *Service, s *proto.Session) { s.State = proto.StateNeedsYou }},
		{"pending interaction count", func(_ *Service, s *proto.Session) { s.PendingInteractions = 1 }},
		{"usage limit error", func(_ *Service, s *proto.Session) { s.TurnErrorKind = proto.TurnErrorUsageLimit }},
		{"terminal pane session", func(_ *Service, s *proto.Session) { s.PaneID = "pane-1" }},
		{"queued hub relay", func(svc *Service, _ *proto.Session) {
			svc.queueRelay(mainID, relayMsg{From: "conv2", Tool: "conversation_send", Text: "waiting", At: time.Now().UTC()})
		}},
		{"recent user input after turn end", func(svc *Service, _ *proto.Session) {
			svc.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
			svc.UserSpoke(ctx, SessionRef{MachineID: "server", SessionID: mainID})
		}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, f, _, _ := newMainAutoCompactTestFixture(t, mainID)
			var compacts atomic.Int32
			f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
				compacts.Add(1)
				return map[string]any{"ok": true}, nil
			})
			row := base
			tc.mutate(s, &row)
			f.setSessions("server", row)
			s.mainAutoCompactTurnSettled(ctx, "server", mainID)
			if compacts.Load() != 0 {
				t.Fatalf("case %q triggered compaction %d times, want 0", tc.name, compacts.Load())
			}
		})
	}
}

type compactHookHub struct {
	*fakeHub
	mu            sync.Mutex
	beforeCompact func()
}

func (h *compactHookHub) setBeforeCompact(fn func()) {
	h.mu.Lock()
	h.beforeCompact = fn
	h.mu.Unlock()
}

func (h *compactHookHub) DeviceCall(ctx context.Context, p DeviceCall) (json.RawMessage, error) {
	if p.Method == DeviceAssistantCompact {
		h.mu.Lock()
		fn := h.beforeCompact
		h.mu.Unlock()
		if fn != nil {
			fn()
		}
	}
	return h.fakeHub.DeviceCall(ctx, p)
}

func TestMainAutoCompactReentrancyCooldownAndRecovery(t *testing.T) {
	ctx := context.Background()
	mainID := "agy-88888888-2222-3333-4444-555555555555"
	f := newFakeHub().withMachine()
	hub := &compactHookHub{fakeHub: f}
	st := &memStore{}
	s := withSettings(t, f, New(Options{Hub: hub, Store: func() Store { return st }, Logf: testLogf(t)}))
	now := time.Date(2026, 11, 24, 12, 0, 0, 0, time.UTC)
	var clockMu sync.Mutex
	s.mainAutoCompact.now = func() time.Time {
		clockMu.Lock()
		defer clockMu.Unlock()
		return now
	}
	advance := func(d time.Duration) {
		clockMu.Lock()
		now = now.Add(d)
		clockMu.Unlock()
	}
	setSettings(t, s, func(v *Settings) {
		v.MachineID = "server"
		v.Main = mainID
		v.MainMachineID = "server"
	})

	startedRPC := make(chan struct{}, 1)
	releaseRPC := make(chan struct{})
	var blockOnce sync.Once
	hub.setBeforeCompact(func() {
		blockOnce.Do(func() {
			startedRPC <- struct{}{}
			<-releaseRPC
		})
	})

	var rpcErr error
	var rpcMu sync.Mutex
	var compacts atomic.Int32
	f.answerNode(DeviceAssistantCompact, func(string, json.RawMessage) (any, error) {
		compacts.Add(1)
		rpcMu.Lock()
		err := rpcErr
		rpcMu.Unlock()
		return map[string]any{"ok": true}, err
	})

	row := proto.Session{
		SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
		Status: proto.StatusDone, Rev: 50, ContextTokens: 350_000, ContextWindow: 1_048_576,
	}
	f.setSessions("server", row)

	// 1. Re-entrancy guard: a second concurrent check while the first RPC is in flight is ignored.
	doneFirst := make(chan struct{})
	go func() {
		s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
		s.mainAutoCompactTurnSettled(ctx, "server", mainID)
		close(doneFirst)
	}()
	<-startedRPC
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	close(releaseRPC)
	<-doneFirst
	if compacts.Load() != 1 {
		t.Fatalf("concurrent checks sent %d compacts, want 1", compacts.Load())
	}

	// 2. While waiting for node CompactionDone (still inFlight), another settled check does not re-trigger.
	row.Rev = 52
	row.ContextTokens = 360_000
	f.setSessions("server", row)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 1 {
		t.Fatalf("in-flight compaction re-triggered: %d", compacts.Load())
	}

	// 3. Compaction finishes -> cooldown prevents immediate re-trigger even if tokens exceed threshold again.
	doneStart := s.mainAutoCompact.clock()
	s.SessionsChanged(ctx, DeviceSessions{
		MachineID: "server",
		Sessions: []proto.Session{{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 53, ContextTokens: 0, ContextWindow: 1_048_576,
			Compaction: &proto.Compaction{State: proto.CompactionDone, StartedAt: doneStart},
		}},
	})
	row.Rev = 60
	row.ContextTokens = 340_000
	f.setSessions("server", row)
	advance(5 * time.Minute)
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 1 {
		t.Fatalf("re-triggered inside cooldown window: %d", compacts.Load())
	}

	// 4. After cooldown expires -> busy refusal backs off briefly and allows retry on next turn end.
	advance(defaultMainAutoCompactCooldown)
	rpcMu.Lock()
	rpcErr = &rpc.Error{Code: "busy", Message: "busy"}
	rpcMu.Unlock()
	s.noteMainAutoCompactTurnEnded(ctx, "server", mainID)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 2 {
		t.Fatalf("expected busy attempt after cooldown: %d", compacts.Load())
	}
	// Inside busy cooldown: skipped.
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 2 {
		t.Fatalf("retried during busy cooldown: %d", compacts.Load())
	}
	// After busy cooldown with error cleared: succeeds even on same Rev watermark because busy cleared it.
	advance(defaultMainAutoCompactBusyCooldown + time.Second)
	rpcMu.Lock()
	rpcErr = nil
	rpcMu.Unlock()
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 3 {
		t.Fatalf("did not retry after busy cooldown expired: %d", compacts.Load())
	}

	// 5. Unknown-method refusal permanently disables auto-compact for this unsupported node/session.
	failStart := s.mainAutoCompact.clock()
	s.SessionsChanged(ctx, DeviceSessions{
		MachineID: "server",
		Sessions: []proto.Session{{
			SessionID: mainID, Assistant: true, Agent: "agy", Launcher: "agy",
			Status: proto.StatusDone, Rev: 61, ContextTokens: 0, ContextWindow: 1_048_576,
			Compaction: &proto.Compaction{State: proto.CompactionDone, StartedAt: failStart},
		}},
	})
	advance(defaultMainAutoCompactCooldown + time.Minute)
	rpcMu.Lock()
	rpcErr = errors.New("unknown method assistant.compact")
	rpcMu.Unlock()
	row.Rev = 70
	row.ContextTokens = 350_000
	f.setSessions("server", row)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 4 {
		t.Fatalf("expected unsupported probe attempt: %d", compacts.Load())
	}
	advance(time.Hour)
	row.Rev = 80
	row.ContextTokens = 380_000
	f.setSessions("server", row)
	s.mainAutoCompactTurnSettled(ctx, "server", mainID)
	if compacts.Load() != 4 {
		t.Fatalf("retried after permanent unknown-method refusal: %d", compacts.Load())
	}
}
