package node

import (
	"context"
	"encoding/json"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/proto"
)

// The configured model's failure classes as the recovery marker names them (#7824).
func TestModelRecoveryReason(t *testing.T) {
	for _, c := range []struct{ text, want string }{
		{"API Error: Opus 5.5's safeguards flagged this message (https://www.anthropic.com/legal/aup).", proto.ModelRecoveryReasonSafeguards},
		{"API Error: Request rejected (429) · upstream account quota usage limit has been reached; an account becomes available again at 15:48 UTC (in 32m)", proto.ModelRecoveryReasonQuota},
		{`API Error: 429 {"error":{"code":"quota_exhausted","message":"..."}}`, proto.ModelRecoveryReasonQuota},
		{"API Error: Request rejected (429) · Too many requests", proto.ModelRecoveryReasonRateLimit},
		{"API Error: 529 overloaded", proto.ModelRecoveryReasonServer},
		{"API Error: 503 no eligible upstream account is currently available", proto.ModelRecoveryReasonServer},
		{"There's an issue with the selected model (x). It may not exist or you may not have access to it.", proto.ModelRecoveryReasonUnavailable},
	} {
		if got := ModelRecoveryReason(c.text); got != c.want {
			t.Errorf("%q: reason %q, want %q", c.text, got, c.want)
		}
	}
}

// A gateway names when an exhausted account is back; the bare clock form is the next such moment in UTC, the dated
// form is read as written. Other words name no time.
func TestGatewayAvailableAgain(t *testing.T) {
	at := time.Date(2026, 11, 14, 15, 16, 0, 0, time.UTC)
	for _, c := range []struct {
		text string
		want time.Time
		ok   bool
	}{
		{"an account becomes available again at 15:48 UTC (in 32m)", time.Date(2026, 11, 14, 15, 48, 0, 0, time.UTC), true},
		{"an account becomes available again at 09:00 UTC (in 17h44m)", time.Date(2026, 11, 15, 9, 0, 0, 0, time.UTC), true},
		{"available again at 2026-11-17 04:03 UTC (in 2d12h)", time.Date(2026, 11, 17, 4, 3, 0, 0, time.UTC), true},
		{"API Error: Rate limit reached", time.Time{}, false},
		{"available again at 25:00 UTC", time.Time{}, false},
	} {
		got, ok := GatewayAvailableAgain(c.text, at)
		if ok != c.ok || !got.Equal(c.want) {
			t.Errorf("%q: %v %v, want %v %v", c.text, got, ok, c.want, c.ok)
		}
	}
}

// recoveryTestNode is a node with conversation id recorded as the main conversation on launcher "claude" asking for
// primary-model, and recovery pinned to an ordered list primary → fallback → last, with sends captured.
func recoveryTestNode(t *testing.T, id string, isMain, followsSetting bool) (*Node, chan modelExecution) {
	t.Helper()
	models, send, probe := assistantRecoveryModels, assistantRecoverySend, assistantModelProbe
	t.Cleanup(func() { assistantRecoveryModels, assistantRecoverySend, assistantModelProbe = models, send, probe })
	assistantRecoveryModels = func(context.Context, *Node, string) (proto.LauncherModels, error) {
		return proto.LauncherModels{Models: []proto.AgentModel{{ID: "primary-model"}, {ID: "fallback-model"}, {ID: "last-model"}}}, nil
	}
	sent := make(chan modelExecution, 8)
	assistantRecoverySend = func(_ context.Context, _ *Node, _ *assistantProc, _, _ string, execution modelExecution) error {
		sent <- execution
		return nil
	}
	assistantModelProbe = func(context.Context, *Node, string, string) bool { return false }
	n := assistantNode(t)
	n.state.put(id, sessionState{Launcher: "claude", Cwd: t.TempDir(), Model: "primary-model", IsMain: isMain, FollowsSetting: followsSetting})
	return n, sent
}

func waitRecoverySend(t *testing.T, sent chan modelExecution) modelExecution {
	t.Helper()
	select {
	case e := <-sent:
		return e
	case <-time.After(3 * time.Second):
		t.Fatal("no recovery send")
	}
	return modelExecution{}
}

// The 23:20 regression: a safeguards refusal on the main conversation's configured model moves it to the next model,
// and the move is recorded where every client reads it, with the configured model, the model now answering and why.
// Before #7824 the node switched silently and the apps said no other model was used.
func TestRecoverySwitchIsVisibleOnTheMainConversation(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	n, sent := recoveryTestNode(t, id, true, false)
	p := &assistantProc{id: id, agent: "claude", launcher: "claude", model: modelExecution{Model: "primary-model"}}
	n.procs[id] = p
	if !n.recoverAssistantResult(p, true, "API Error: Opus 5.5's safeguards flagged this message") {
		t.Fatal("the refusal was not recovered")
	}
	if e := waitRecoverySend(t, sent); e.Model != "fallback-model" {
		t.Fatalf("recovery sent on %q", e.Model)
	}
	waitRecovery(t, "the marker", func() bool { return n.modelRecovery(id) != nil })
	r := n.modelRecovery(id)
	if r.From != "primary-model" || r.To != "fallback-model" || r.Reason != proto.ModelRecoveryReasonSafeguards || r.ChainExhausted {
		t.Fatalf("marker %+v", r)
	}
	if len(r.Tried) < 2 || r.Tried[0] != "primary-model" || r.Tried[len(r.Tried)-1] != "fallback-model" {
		t.Fatalf("tried %v", r.Tried)
	}
	// The session row carries it in the shape the shipped apps already draw as "switched to X".
	s := proto.Session{SessionID: id}
	n.decorateModelRecovery(&s)
	f := s.ModelFallback
	if f == nil || f.Subtype != "model_refusal_fallback" || f.OriginalModel != "primary-model" || f.FallbackModel != "fallback-model" ||
		f.Category != proto.ModelRecoveryReasonSafeguards || f.Scope != "session" {
		t.Fatalf("projected fallback %+v", f)
	}
	// Persisted with the session's record, so a node restart keeps it.
	if st, _ := n.state.get(id); st.ModelRecovery == nil || st.ModelRecovery.To != "fallback-model" {
		t.Fatalf("not persisted: %+v", st.ModelRecovery)
	}
	// It never changes the configured model.
	if st, _ := n.state.get(id); st.Model != "primary-model" {
		t.Fatalf("the configured model changed to %q", st.Model)
	}
}

// The owner's required round trip: Opus refused → the conversation answers on the next model → Opus is available again
// → the very next request runs on Opus, not on the cheaper model for the rest of the hold window.
func TestRecoveryReturnsToTheConfiguredModelOnTheNextRequest(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	n, sent := recoveryTestNode(t, id, true, false)
	p := &assistantProc{id: id, agent: "claude", launcher: "claude", model: modelExecution{Model: "primary-model"}}
	n.procs[id] = p
	if !n.recoverAssistantResult(p, true, "API Error: Request rejected (429) · upstream account quota usage limit has been reached; an account becomes available again at 23:59 UTC (in 32m)", "", assistantRecordKind) {
		t.Fatal("the quota failure was not recovered")
	}
	// The gateway quota 429 gets one same-model try first (#3788), then the switch.
	first := waitRecoverySend(t, sent)
	if first.Model == "primary-model" {
		p2 := &assistantProc{id: id, agent: "claude", launcher: "claude", model: first}
		n.mu.Lock()
		n.procs[id] = p2
		n.mu.Unlock()
		waitRecovery(t, "the first attempt to finish", func() bool {
			n.mu.Lock()
			defer n.mu.Unlock()
			r := n.recoveries[id]
			return r != nil && !r.running
		})
		if !n.recoverAssistantResult(p2, true, "API Error: Request rejected (429) · upstream account quota usage limit has been reached; an account becomes available again at 23:59 UTC (in 32m)", "", assistantRecordKind) {
			t.Fatal("the second quota failure was not recovered")
		}
		first = waitRecoverySend(t, sent)
	}
	if first.Model != "fallback-model" {
		t.Fatalf("switched to %q", first.Model)
	}
	waitRecovery(t, "the marker", func() bool { r := n.modelRecovery(id); return r != nil && r.To == "fallback-model" })
	if r := n.modelRecovery(id); r.Reason != proto.ModelRecoveryReasonQuota || r.ResetsAt == nil {
		t.Fatalf("quota marker %+v", r)
	}
	// The fallback answered: the hold starts, as before.
	fp := &assistantProc{id: id, agent: "claude", launcher: "claude", model: modelExecution{Model: "fallback-model"}}
	n.mu.Lock()
	n.procs[id] = fp
	n.mu.Unlock()
	n.clearAssistantRecovery(fp, true)
	asked := modelExecution{Model: "primary-model"}
	if got := n.heldFallback(id, "claude", asked); got.Model != "fallback-model" {
		t.Fatalf("the hold did not start: %q", got.Model)
	}

	// While the configured model still fails its probe, the next request stays on the fallback.
	var probes atomic.Int32
	var available atomic.Bool
	assistantModelProbe = func(_ context.Context, _ *Node, launcher, model string) bool {
		probes.Add(1)
		if model != "primary-model" {
			t.Errorf("probed %q, want the configured model", model)
		}
		return available.Load()
	}
	if n.returnToConfiguredAt(context.Background(), id, time.Now()) {
		t.Fatal("returned while the configured model still fails")
	}
	// The configured model comes back: the probe loop marks the return, and the next request runs on it.
	available.Store(true)
	n.probeModelRecoveries(context.Background())
	if r := n.modelRecovery(id); r == nil || !r.Returning {
		t.Fatalf("the probe did not mark the return: %+v", r)
	}
	if !n.returnToConfiguredAt(context.Background(), id, time.Now()) {
		t.Fatal("did not return although the configured model answers")
	}
	exec := n.heldFallback(id, "claude", asked)
	if n.recoveryProtected(id) && n.returnToConfiguredAt(context.Background(), id, time.Now()) {
		marker := n.modelRecovery(id)
		n.releaseModelRecovery(id, marker.From)
		exec.Model = marker.From
	}
	if exec.Model != "primary-model" {
		t.Fatalf("the next request runs on %q, want the configured model", exec.Model)
	}
	if n.modelRecovery(id) != nil {
		t.Fatal("the marker outlived the return")
	}
	if got := n.heldFallback(id, "claude", asked); got.Model != "primary-model" {
		t.Fatalf("the hold outlived the return: %q", got.Model)
	}
	if probes.Load() == 0 {
		t.Fatal("never probed")
	}
}

// A worker Agent's conversation keeps its own fallback behavior: no marker, no forced return.
func TestRecoveryMarkerOnlyForMainAndLeaders(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	for _, c := range []struct {
		name            string
		isMain, follows bool
		want            bool
	}{
		{"main", true, false, true},
		{"leader", false, true, true},
		{"worker", false, false, false},
	} {
		n, sent := recoveryTestNode(t, id, c.isMain, c.follows)
		p := &assistantProc{id: id, agent: "claude", launcher: "claude", model: modelExecution{Model: "primary-model"}}
		n.procs[id] = p
		if !n.recoverAssistantResult(p, true, "API Error: Opus 5.5's safeguards flagged this message") {
			t.Fatalf("%s: not recovered", c.name)
		}
		waitRecoverySend(t, sent)
		time.Sleep(50 * time.Millisecond)
		if got := n.modelRecovery(id) != nil; got != c.want {
			t.Errorf("%s: marker %v, want %v", c.name, got, c.want)
		}
	}
}

// Every model of the chain failing ends on an exhausted marker: the failure card's cause, reset time and Retry come
// from it, and it is projected without a fallback model so the apps' fallback card does not claim a switch.
func TestRecoveryChainExhausted(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	models := assistantRecoveryModels
	n, sent := recoveryTestNode(t, id, true, false)
	assistantRecoveryModels = func(context.Context, *Node, string) (proto.LauncherModels, error) {
		return proto.LauncherModels{Models: []proto.AgentModel{{ID: "primary-model"}}}, nil // nothing to move to
	}
	t.Cleanup(func() { assistantRecoveryModels = models })
	p := &assistantProc{id: id, agent: "claude", launcher: "claude", model: modelExecution{Model: "primary-model"}}
	n.procs[id] = p
	const quota = "API Error: Request rejected (429) · upstream account quota usage limit has been reached; an account becomes available again at 23:59 UTC (in 32m)"
	if !n.recoverAssistantResult(p, true, quota, "", assistantRecordKind) {
		t.Fatal("not recovered")
	}
	if e := waitRecoverySend(t, sent); e.Model != "primary-model" {
		t.Fatalf("same-model try on %q", e.Model)
	}
	waitRecovery(t, "the attempt to finish", func() bool {
		n.mu.Lock()
		defer n.mu.Unlock()
		r := n.recoveries[id]
		return r != nil && !r.running
	})
	if !n.recoverAssistantResult(p, true, quota, "", assistantRecordKind) {
		t.Fatal("the second failure was not taken")
	}
	waitRecovery(t, "the exhausted marker", func() bool { r := n.modelRecovery(id); return r != nil && r.ChainExhausted })
	r := n.modelRecovery(id)
	if r.Reason != proto.ModelRecoveryReasonQuota || r.ResetsAt == nil || r.ResetsAt.UTC().Format("15:04") != "23:59" {
		t.Fatalf("exhausted marker %+v", r)
	}
	s := proto.Session{SessionID: id}
	n.decorateModelRecovery(&s)
	if s.ModelFallback == nil || !s.ModelFallback.RecoveryExhausted || s.ModelFallback.FallbackModel != "" || s.ModelFallback.RecoveryReset == nil {
		t.Fatalf("projection %+v", s.ModelFallback)
	}
}

// The runtime's own refusal fallback is first-hand evidence and is never replaced by the node's projection.
func TestRuntimeFallbackWinsOverTheProjection(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	n, _ := recoveryTestNode(t, id, true, false)
	n.putModelRecovery(id, &proto.ModelRecovery{From: "primary-model", To: "fallback-model", Reason: proto.ModelRecoveryReasonSafeguards})
	own := &proto.ModelRefusal{Subtype: "model_refusal_fallback", OriginalModel: "primary-model", FallbackModel: "runtime-pick"}
	s := proto.Session{SessionID: id, ModelFallback: own}
	n.decorateModelRecovery(&s)
	if s.ModelFallback != own {
		t.Fatalf("the runtime's record was replaced: %+v", s.ModelFallback)
	}
}

// The marker survives a node restart through the session record (state.load), so the apps still say which model
// answers after a redeploy, and the probe loop can still bring the conversation back.
func TestRecoveryMarkerSurvivesARestart(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	n, _ := recoveryTestNode(t, id, true, false)
	n.putModelRecovery(id, &proto.ModelRecovery{From: "primary-model", To: "fallback-model", Reason: proto.ModelRecoveryReasonQuota})
	st, _ := n.state.get(id)
	b, err := json.Marshal(map[string]sessionState{id: st})
	if err != nil {
		t.Fatal(err)
	}
	restarted := assistantNode(t)
	var loaded map[string]sessionState
	if err := json.Unmarshal(b, &loaded); err != nil {
		t.Fatal(err)
	}
	restarted.state.merge(loaded)
	if r := restarted.modelRecovery(id); r == nil || r.From != "primary-model" || r.To != "fallback-model" {
		t.Fatalf("after a restart: %+v", r)
	}
	if !restarted.recoveryProtected(id) {
		t.Fatal("the main conversation lost its protection")
	}
}

// Compacting a conversation summarizes the refused turns away: the temporary switch ends with them.
func TestCompactionEndsTheRecovery(t *testing.T) {
	const id = "11111111-2222-3333-4444-555555555555"
	n, _ := recoveryTestNode(t, id, true, false)
	n.putModelRecovery(id, &proto.ModelRecovery{From: "primary-model", To: "fallback-model"})
	n.contextCompacted(id)
	if n.modelRecovery(id) != nil {
		t.Fatal("the marker outlived the compaction")
	}
	if st, _ := n.state.get(id); st.ModelRecovery != nil {
		t.Fatal("the persisted marker outlived the compaction")
	}
}

// The probe never asks with the fallback's name, and only a 2xx counts: a 429, 5xx or auth failure keeps the fallback.
func TestModelProbeStatuses(t *testing.T) {
	for _, c := range []struct {
		status int
		want   bool
	}{{200, true}, {429, false}, {500, false}, {529, false}, {401, false}, {404, false}} {
		got := probeStatusOK(c.status)
		if got != c.want {
			t.Errorf("status %d: available %v, want %v", c.status, got, c.want)
		}
	}
}

func waitRecovery(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

