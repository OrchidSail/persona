package hub

import (
	"bytes"
	"context"
	"encoding/json"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/bus"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/identity"
	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

// agentEar is what a manager's extensions were sent, as the hub's extensions.emit carried it.
type agentEar struct {
	h     *Hub
	f     *guardManager
	mu    sync.Mutex
	got   []extwire.Emit
	taken int // how many of got next has returned
	marks int
}

func agentEarOf(h *Hub, f *guardManager) *agentEar {
	e := &agentEar{h: h, f: f}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.onEvent = func(method string, params json.RawMessage) {
		var em extwire.Emit
		if method != extwire.EventEmit || json.Unmarshal(params, &em) != nil {
			return
		}
		e.mu.Lock()
		e.got = append(e.got, em)
		e.mu.Unlock()
	}
	return e
}

// next is the agent.changed events heard since the last call, as their params were sent, once the hub has nothing
// more to send: its backlog is empty, and a mark sent after it on the same connection has arrived (the bus keeps the
// order), so nothing sent before the mark is still on its way.
func (e *agentEar) next(t *testing.T) []string {
	t.Helper()
	q := &e.h.agentChanges
	eventually(t, 5*time.Second, "the backlog of agent.changed to empty", func() bool {
		q.mu.Lock()
		defer q.mu.Unlock()
		return !q.running && len(q.pending) == 0
	})
	e.marks++
	mark := "test.mark." + strconv.Itoa(e.marks)
	e.h.emitToExtensions(e.f.id, mark, map[string]any{})
	eventually(t, 5*time.Second, "the mark to arrive", func() bool {
		e.mu.Lock()
		defer e.mu.Unlock()
		return slices.ContainsFunc(e.got, func(em extwire.Emit) bool { return em.Event == mark })
	})
	e.mu.Lock()
	defer e.mu.Unlock()
	var out []string
	for _, em := range e.got[e.taken:] {
		if em.Event == eventAgentChanged {
			out = append(out, string(em.Params))
		}
	}
	e.taken = len(e.got)
	return out
}

func (e *agentEar) want(t *testing.T, what string, want ...string) {
	t.Helper()
	if got := e.next(t); !slices.Equal(got, want) {
		t.Fatalf("%s: the extensions were sent %q, want %q", what, got, want)
	}
}

func tellCard(t *testing.T, h *Hub, f *guardManager, ix proto.Interaction) {
	t.Helper()
	if err := f.peer.Notify(assistantwire.EventFleetInteraction, assistantwire.FleetInteraction{Interaction: ix}); err != nil {
		t.Fatal(err)
	}
	eventually(t, 5*time.Second, "card "+ix.ID, func() bool { _, ok := h.pendingInteraction(ix.ID); return ok })
}

func endCard(t *testing.T, h *Hub, f *guardManager, id string) {
	t.Helper()
	if err := f.peer.Notify(assistantwire.EventFleetResolved, assistantwire.FleetResolved{ID: id}); err != nil {
		t.Fatal(err)
	}
	eventually(t, 5*time.Second, "card "+id+" over", func() bool { _, ok := h.pendingInteraction(id); return !ok })
}

func stopHook(h *Hub, machine, sid string) {
	raw, _ := json.Marshal(map[string]any{"session_id": sid, "event": "Stop", "cwd": "/work", "last_assistant_message": "SECRET-LAST-WORDS"})
	h.localMachine(machine).onEvent("hook.event", raw)
}

func maSessions(h *Hub, rows ...proto.Session) {
	for i := range rows {
		rows[i].MachineID = "ma"
	}
	h.takeFleetSessions(assistantwire.FleetSessions{MachineID: "ma", Sessions: rows})
}

const (
	s3Changed = `{"machine_id":"ma","session_id":"s3"}`
	s3Ended   = `{"machine_id":"ma","session_id":"s3","ended":true}`
)

// The payload is the computer, the session and whether a turn ended, as extensions.emit carries it: no title, no
// state, no text, whatever the row, the card or the hook that caused it held.
func TestAgentChangedPayload(t *testing.T) {
	for want, c := range map[string]agentChanged{
		s3Changed: {MachineID: "ma", SessionID: "s3"},
		s3Ended:   {MachineID: "ma", SessionID: "s3", Ended: true},
	} {
		if got, _ := json.Marshal(c); string(got) != want {
			t.Errorf("%+v is sent as %s, want %s", c, got, want)
		}
	}
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	ear.next(t)
	maSessions(h, proto.Session{SessionID: "s3", Title: "SECRET-TITLE", Status: proto.StatusWorking, State: proto.StateWorking,
		Cwd: "/work/SECRET-DIR", Queued: []string{"SECRET-QUEUED"}, Model: "SECRET-MODEL"})
	ear.want(t, "a row that started working", s3Changed)
	tellCard(t, h, a, proto.Interaction{ID: "ix_1", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxPermission, Summary: "SECRET-SUMMARY"})
	ear.want(t, "a card raised", s3Changed)
	stopHook(h, "ma", "s3")
	ear.want(t, "a Stop hook", s3Ended)
	ear.mu.Lock()
	defer ear.mu.Unlock()
	for _, em := range ear.got {
		if raw, _ := json.Marshal(em); em.Event == eventAgentChanged && string(raw) != `{"event":"agent.changed","params":`+string(em.Params)+`}` {
			t.Errorf("extensions.emit carried %s", raw)
		}
		if strings.Contains(string(em.Params), "SECRET") {
			t.Errorf("the event carries what it is about: %s", em.Params)
		}
	}
}

// A headless conversation (the main one) stays listed working while a permission card waits on its hook: its
// status never moves, so only the card itself, and the row's count of waiting cards, say that it needs its owner.
// The extensions are told of both, and of the card being answered, and none of it is a turn's end.
func TestAgentChangedWhileAWorkingConversationWaitsOnACard(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	working := proto.Session{SessionID: "s3", Assistant: true, Status: proto.StatusWorking, State: proto.StateWorking}
	maSessions(h, working)
	ear.next(t)

	tellCard(t, h, a, proto.Interaction{ID: "ix_perm", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxPermission, Summary: "Run a command"})
	ear.want(t, "the card raised, the row as it was", s3Changed)

	waiting := working
	waiting.State, waiting.PendingInteractions = proto.StateNeedsYou, 1
	maSessions(h, waiting)
	ear.want(t, "the row says a card waits, its status still working", s3Changed)

	endCard(t, h, a, "ix_perm")
	ear.want(t, "the card answered", s3Changed)
	maSessions(h, working)
	ear.want(t, "the row works on", s3Changed)

	// Telling the same card again is not raising it; a later occurrence under its id is.
	at := time.Unix(1_800_000_000, 0).UTC()
	again := proto.Interaction{ID: "ix_again", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxQuestion, CreatedAt: at}
	tellCard(t, h, a, again)
	ear.want(t, "a second card", s3Changed)
	again.Summary = "now with a summary"
	if err := a.peer.Notify(assistantwire.EventFleetInteraction, assistantwire.FleetInteraction{Interaction: again}); err != nil {
		t.Fatal(err)
	}
	eventually(t, 5*time.Second, "the card told again", func() bool { ix, _ := h.pendingInteraction("ix_again"); return ix.Summary != "" })
	ear.want(t, "the same card told again")
	again.CreatedAt = at.Add(time.Minute)
	if err := a.peer.Notify(assistantwire.EventFleetInteraction, assistantwire.FleetInteraction{Interaction: again}); err != nil {
		t.Fatal(err)
	}
	eventually(t, 5*time.Second, "its next occurrence", func() bool { ix, _ := h.pendingInteraction("ix_again"); return ix.CreatedAt.Equal(again.CreatedAt) })
	ear.want(t, "its next occurrence", s3Changed)

	// A reconnecting manager's whole list of cards: the ones it no longer has are over, a new one is raised, the one
	// that stays is neither.
	h.takeFleetInteractionsFrom(assistantwire.AssistantID, []proto.Interaction{
		{ID: "ix_new", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxQuestion},
	})
	// Two changes of one session at once: one event or two, as the first was sent before the second came or not.
	if got := ear.next(t); len(got) == 0 || len(got) > 2 || slices.ContainsFunc(got, func(p string) bool { return p != s3Changed }) {
		t.Fatalf("a card over and a card raised by the manager's list: the extensions were sent %q", got)
	}
	h.takeFleetInteractionsFrom(assistantwire.AssistantID, []proto.Interaction{
		{ID: "ix_new", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxQuestion},
	})
	ear.want(t, "the same list again")

	// None of it watched the session or read its transcript on the computer.
	for _, call := range []string{"session.watch", "session.items", "session.info"} {
		if strings.Contains(a.log(), call) {
			t.Errorf("telling the extensions asked the computer for %s:\n%s", call, a.log())
		}
	}
}

// ended is said once when a session's status leaves working, and once when its Stop hook runs; a change that leaves
// it working, or one of a session that was not working, does not say it.
func TestAgentChangedSaysATurnEnded(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	row := func(status, state string) proto.Session {
		return proto.Session{SessionID: "s3", Status: status, State: state}
	}
	maSessions(h, row(proto.StatusIdle, proto.StateIdle))
	ear.next(t)

	maSessions(h, row(proto.StatusWorking, proto.StateWorking))
	ear.want(t, "it starts working", s3Changed)
	maSessions(h, row(proto.StatusIdle, proto.StateUnread))
	ear.want(t, "its status left working", s3Ended)
	maSessions(h, row(proto.StatusIdle, proto.StateIdle))
	ear.want(t, "its owner read it", s3Changed)

	maSessions(h, row(proto.StatusWorking, proto.StateWorking))
	ear.want(t, "it works again", s3Changed)
	stopHook(h, "ma", "s3")
	ear.want(t, "its Stop hook ran, the row still working", s3Ended)
	maSessions(h, row(proto.StatusBlocked, proto.StateNeedsYou))
	ear.want(t, "it stopped on a dialog", s3Ended)

	// Another hook is no end, and a hook that names no session is nobody's.
	raw, _ := json.Marshal(map[string]any{"session_id": "s3", "event": "PreToolUse"})
	h.localMachine("ma").onEvent("hook.event", raw)
	stopHook(h, "ma", "")
	ear.want(t, "a hook that is not a Stop, a Stop of no session")
}

// What the event is about is the state a client reads, not the conversation's steps: a new title, a new revision, new
// queued input, a subagent's step or a row told again as it was change nothing. A subagent starting or finishing, a
// background command, a turn's error and the triage's phase do.
func TestAgentChangedOnlyOfTheState(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	base := proto.Session{SessionID: "s3", Title: "one", Status: proto.StatusWorking, State: proto.StateWorking,
		Subagents: []proto.Subagent{{ID: "a1", Running: true, Steps: 1}}}
	maSessions(h, base)
	ear.next(t)

	same := base
	same.Title, same.Rev, same.Queued, same.ContextTokens = "two", 7, []string{"later"}, 1200
	same.Subagents = []proto.Subagent{{ID: "a1", Running: true, Steps: 9, LastText: "reading", Tokens: 500}}
	maSessions(h, same)
	maSessions(h, same)
	ear.want(t, "nothing of its state changed")

	for _, tc := range []struct {
		what string
		set  func(*proto.Session)
	}{
		{"a subagent finished", func(s *proto.Session) { s.Subagents = []proto.Subagent{{ID: "a1"}} }},
		{"a second subagent", func(s *proto.Session) { s.Subagents = []proto.Subagent{{ID: "a1"}, {ID: "a2", Running: true}} }},
		{"a background command", func(s *proto.Session) { s.Background = []proto.BackgroundTask{{}} }},
		{"a turn's error", func(s *proto.Session) { s.TurnError = "overloaded" }},
		{"the triage's phase", func(s *proto.Session) { s.Work = &proto.WorkState{Phase: "done"} }},
		{"its tab is being opened", func(s *proto.Session) { s.Starting = true }},
	} {
		tc.set(&same)
		maSessions(h, same)
		ear.want(t, tc.what, s3Changed)
	}

	// A session new to the list is a change; one gone from it is not told of (the hub no longer lists it).
	maSessions(h, same, proto.Session{SessionID: "s4", Status: proto.StatusWorking})
	ear.want(t, "a session new to the list", `{"machine_id":"ma","session_id":"s4"}`)
	maSessions(h, same)
	ear.want(t, "a session gone from the list")
}

// The first list of a computer is where the hub starts from: the extensions are told of what changes after it, not
// of every session there already was.
func TestAgentChangedStartsFromTheFirstList(t *testing.T) {
	var q agentChanges
	list := []proto.Session{{SessionID: "s1", Status: proto.StatusWorking}, {SessionID: "s2"}}
	if got := q.rows("ma", list); len(got) != 0 {
		t.Fatalf("the first list told of %+v", got)
	}
	if got := q.rows("ma", list); len(got) != 0 {
		t.Fatalf("the same list told of %+v", got)
	}
	list[0].Status = proto.StatusIdle
	if got := q.rows("ma", list); !slices.Equal(got, []agentChanged{{MachineID: "ma", SessionID: "s1", Ended: true}}) {
		t.Fatalf("s1 left working: %+v", got)
	}
	if got := q.rows("mb", list); len(got) != 0 {
		t.Fatalf("another computer's first list told of %+v", got)
	}
	// Gone and back: forgotten, so its coming back is a session new to the list, not an end.
	if got := q.rows("ma", list[1:]); len(got) != 0 {
		t.Fatalf("a session gone told of %+v", got)
	}
	if got := q.rows("ma", list); !slices.Equal(got, []agentChanged{{MachineID: "ma", SessionID: "s1"}}) {
		t.Fatalf("s1 back in the list: %+v", got)
	}
}

// Never of a session or a card the extensions are not shown (extension_private.go): a space's conversation, whatever
// changes of it (its row, its card, its Stop hook), a private card, a card in a space's directory, a card or a hook
// of a session the hub does not list.
func TestAgentChangedIsNotSentOfWhatExtensionsAreNotShown(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	health := identity.HealthDir("/home/me")
	shown := proto.Session{SessionID: "s3", Status: proto.StatusWorking, State: proto.StateWorking}
	space := proto.Session{SessionID: "sh", Cwd: health, Assistant: true, Space: "health", Status: proto.StatusWorking, State: proto.StateWorking}
	byDir := proto.Session{SessionID: "sd", Cwd: health, Status: proto.StatusWorking, State: proto.StateWorking}
	maSessions(h, shown, space, byDir)
	ear.next(t)

	space.Status, space.State, space.PendingInteractions = proto.StatusIdle, proto.StateNeedsYou, 1
	byDir.Status, byDir.State = proto.StatusIdle, proto.StateUnread
	maSessions(h, shown, space, byDir)
	stopHook(h, "ma", "sh")
	stopHook(h, "ma", "sd")
	stopHook(h, "ma", "never-listed")
	for _, ix := range []proto.Interaction{
		{ID: "ix_health", MachineID: "ma", SessionID: "sh", Kind: proto.IxQuestion},
		{ID: "ix_health_cwd", MachineID: "ma", SessionID: "s3", Cwd: health, Kind: proto.IxQuestion},
		{ID: "ix_private", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxQuestion, Private: true},
		{ID: "ix_unknown", MachineID: "ma", SessionID: "never-listed", Cwd: "/work", Kind: proto.IxQuestion},
		{ID: "ix_no_session", MachineID: "ma", Cwd: "/work", Kind: proto.IxQuestion},
	} {
		tellCard(t, h, a, ix)
		endCard(t, h, a, ix.ID)
	}
	ear.want(t, "what the extensions are not shown")

	// A session they were shown that moves into a space is not told of from then on, not even that it changed.
	shown.Space, shown.Status = "health", proto.StatusIdle
	maSessions(h, shown, space, byDir)
	ear.want(t, "a session that became a space's")

	shown.Space, shown.Status = "", proto.StatusWorking
	maSessions(h, shown, space, byDir)
	ear.want(t, "the session they are shown again", s3Changed)
	tellCard(t, h, a, proto.Interaction{ID: "ix_ok", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxQuestion})
	ear.want(t, "a card of it they are shown", s3Changed)
}

// A session is its computer's manager's: only that manager's extensions are told, never another's.
func TestAgentChangedGoesToTheSessionsManagerOnly(t *testing.T) {
	h, a, b := pathsHub(t, quiet, quiet)
	earA, earB := agentEarOf(h, a), agentEarOf(h, b)
	earA.next(t)
	earB.next(t)

	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusWorking})
	tellCard(t, h, a, proto.Interaction{ID: "ix_a", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxPermission})
	stopHook(h, "ma", "s3")
	if got := earA.next(t); len(got) == 0 || slices.ContainsFunc(got, func(p string) bool { return !strings.Contains(p, `"machine_id":"ma"`) }) {
		t.Fatalf("A's extensions were sent %q", got)
	}
	earB.want(t, "B's extensions, of A's session")

	h.takeFleetSessions(assistantwire.FleetSessions{MachineID: "mb", Sessions: []proto.Session{{MachineID: "mb", SessionID: "b1", Status: proto.StatusWorking}}})
	tellCard(t, h, b, proto.Interaction{ID: "ix_b", MachineID: "mb", SessionID: "b1", Cwd: "/work", Kind: proto.IxPermission})
	// B names A's session on a card of its own: the hub drops the card (cardAllowed), and tells nobody.
	if err := b.peer.Notify(assistantwire.EventFleetInteraction, assistantwire.FleetInteraction{
		Interaction: proto.Interaction{ID: "ix_forged", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxPermission}}); err != nil {
		t.Fatal(err)
	}
	endCard(t, h, b, "ix_b") // after the forged one on the same connection: it was taken, and dropped
	if got := earB.next(t); len(got) == 0 || slices.ContainsFunc(got, func(p string) bool { return !strings.Contains(p, `"machine_id":"mb","session_id":"b1"`) }) {
		t.Fatalf("B's extensions were sent %q", got)
	}
	earA.want(t, "A's extensions, of B's session")
}

// holdAgentChanges keeps the backlog from draining, as while a send to a slow manager is under way; the returned
// func says what waits, in order.
func holdAgentChanges(h *Hub) (waiting func() []agentKey) {
	q := &h.agentChanges
	q.mu.Lock()
	q.running = true
	q.mu.Unlock()
	return func() []agentKey {
		q.mu.Lock()
		defer q.mu.Unlock()
		if len(q.pending) != len(q.order) {
			panic("the backlog's order and its entries differ")
		}
		return slices.Clone(q.order)
	}
}

// An event that waited is not sent once its session is no longer one the extensions are shown: a session that
// became a space's conversation, or left the list, while its event waited behind a slow manager. The event waiting
// is withdrawn as the list changes, and the one already taken out to be sent is checked again before it goes.
func TestAgentChangedWaitingIsNotSentOnceTheSessionIsHidden(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	idle := func(id string) proto.Session { return proto.Session{SessionID: id, Status: proto.StatusIdle} }
	working := func(id string) proto.Session { return proto.Session{SessionID: id, Status: proto.StatusWorking} }
	maSessions(h, idle("s3"), idle("s4"), idle("s5"))
	ear.next(t)
	owner := assistantwire.AssistantID
	s3, s4, s5 := agentKey{owner, "ma", "s3"}, agentKey{owner, "ma", "s4"}, agentKey{owner, "ma", "s5"}

	waiting := holdAgentChanges(h)
	maSessions(h, working("s3"), working("s4"), working("s5"))
	stopHook(h, "ma", "s3")
	tellCard(t, h, a, proto.Interaction{ID: "ix_4", MachineID: "ma", SessionID: "s4", Cwd: "/work", Kind: proto.IxPermission})
	if got := waiting(); !slices.Equal(got, []agentKey{s3, s4, s5}) {
		t.Fatalf("waiting: %v", got)
	}
	// s3 becomes a space's conversation by its own Space, s4 by its directory, and s5 stays.
	hidden3, hidden4 := working("s3"), working("s4")
	hidden3.Space, hidden4.Cwd = "health", identity.HealthDir("/home/me")
	maSessions(h, hidden3, hidden4, working("s5"))
	if !h.sessionHiddenFromExtensions("ma", "s3") || !h.sessionHiddenFromExtensions("ma", "s4") {
		t.Fatal("the sessions are not hidden")
	}
	if got := waiting(); !slices.Equal(got, []agentKey{s5}) {
		t.Fatalf("the events of the sessions that became a space's still wait: %v", got)
	}
	// One taken out to be sent just before its session was hidden: checked as it is sent.
	h.sendAgentChanged(s3, true)
	h.sendAgentChanged(s4, false)
	go h.agentChanges.drain(h.sendAgentChanged)
	ear.want(t, "after two sessions became a space's", `{"machine_id":"ma","session_id":"s5"}`)

	// The same for a session that leaves the list (deleted, archived) while its event waits.
	waiting = holdAgentChanges(h)
	maSessions(h, hidden3, hidden4, idle("s5"))
	if got := waiting(); !slices.Equal(got, []agentKey{s5}) {
		t.Fatalf("waiting: %v", got)
	}
	maSessions(h, hidden3, hidden4)
	if got := waiting(); len(got) != 0 {
		t.Fatalf("the event of a session gone from the list still waits: %v", got)
	}
	h.sendAgentChanged(s5, true)
	h.sendAgentChanged(agentKey{owner, "ma", "never-listed"}, false)
	h.sendAgentChanged(agentKey{owner, "never-a-computer", "s3"}, false)
	go h.agentChanges.drain(h.sendAgentChanged)
	ear.want(t, "after the session left the list")

	// Out of the space again, it is a session new to what the extensions are shown, whatever happened in between:
	// with the state it had there, and with a turn that ended there, it is told of once, and not as a turn's end.
	maSessions(h, working("s3"), hidden4)
	ear.want(t, "s3 out of the space, its state as it was there", s3Changed)
	hidden3.Status = proto.StatusWorking
	maSessions(h, hidden3, hidden4)
	ear.want(t, "s3 in the space again")
	maSessions(h, idle("s3"), hidden4)
	ear.want(t, "s3 out of the space, its turn ended there", s3Changed)
}

// An event that waited is not sent to a manager whose computer it no longer is: the computer went to another manager
// while the event waited. The event waiting is withdrawn as the computer is handed over, and the one already taken
// out to be sent is checked again before it goes. What changes afterwards is the new manager's extensions' to hear.
func TestAgentChangedWaitingIsNotSentToAFormerManager(t *testing.T) {
	h, a, b := pathsHub(t, quiet, quiet)
	earA, earB := agentEarOf(h, a), agentEarOf(h, b)
	earA.next(t)
	earB.next(t)
	was := agentKey{a.id, "ma", "s3"}

	waiting := holdAgentChanges(h)
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusWorking})
	h.takeFleetSessions(assistantwire.FleetSessions{MachineID: "mb", Sessions: []proto.Session{{MachineID: "mb", SessionID: "b1", Status: proto.StatusWorking}}})
	if got := waiting(); !slices.Equal(got, []agentKey{was, {b.id, "mb", "b1"}}) {
		t.Fatalf("waiting: %v", got)
	}
	if _, err := h.moveMachine(context.Background(), "ma", b.id); err != nil {
		t.Fatal(err)
	}
	if h.machineOwner("ma") != b.id {
		t.Fatal("the computer did not move")
	}
	for _, k := range waiting() {
		if k.owner == a.id {
			t.Fatalf("an event still waits for the manager the computer left: %v", waiting())
		}
	}
	// One taken out to be sent just before the computer moved: checked as it is sent.
	h.sendAgentChanged(was, true)
	go h.agentChanges.drain(h.sendAgentChanged)
	earA.want(t, "the former manager's extensions")
	// B hears of its own b1, and of ma's s3 as its own from the hand-over on (the hub lists a computer handed over
	// offline until its new manager reports it, so s3 left working), never the event noted for A.
	if got := earB.next(t); !slices.Contains(got, `{"machine_id":"mb","session_id":"b1"}`) || slices.ContainsFunc(got, func(p string) bool {
		return p != `{"machine_id":"mb","session_id":"b1"}` && p != s3Ended
	}) {
		t.Fatalf("the manager the computer went to was sent %q", got)
	}

	// From now on ma's sessions are B's extensions' to hear of, never A's.
	h.takeFleetSessions(assistantwire.FleetSessions{MachineID: "ma", Sessions: []proto.Session{{MachineID: "ma", SessionID: "s3", Status: proto.StatusIdle, State: proto.StateUnread}}})
	stopHook(h, "ma", "s3")
	if got := earB.next(t); len(got) == 0 || slices.ContainsFunc(got, func(p string) bool { return !strings.Contains(p, `"machine_id":"ma","session_id":"s3"`) }) {
		t.Fatalf("the manager the computer went to was sent %q", got)
	}
	earA.want(t, "the former manager's extensions, of what changed after the move")
}

// agentHeldBus is a manager's bus connection whose transport the test holds: the first line with hold in it stays in
// Write until unhold, as on a connection the manager has stopped reading; everything written is kept.
type agentHeldBus struct {
	peer    *rpc.Peer
	hold    []byte
	once    sync.Once
	entered chan struct{} // closed when the held line is in Write
	release chan struct{}
	unheld  sync.Once
	mu      sync.Mutex
	buf     bytes.Buffer
}

func (w *agentHeldBus) Write(p []byte) (int, error) {
	if bytes.Contains(p, w.hold) {
		w.once.Do(func() { close(w.entered); <-w.release })
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.buf.Write(p)
}

func (w *agentHeldBus) unhold() { w.unheld.Do(func() { close(w.release) }) }

// written is every line that reached the transport so far.
func (w *agentHeldBus) written() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.buf.String()
}

// holdBus puts a held connection in manager f's place in the hub's roster, until the test ends: what the hub sends
// f goes through the hub's own rpc.Peer writer into the test's transport.
func holdBus(t *testing.T, h *Hub, f *guardManager, hold string) *agentHeldBus {
	t.Helper()
	w := &agentHeldBus{hold: []byte(hold), entered: make(chan struct{}), release: make(chan struct{})}
	w.peer = rpc.New("h", w)
	h.bus.mu.Lock()
	original := h.bus.current[f.id]
	h.bus.current[f.id] = &busConn{peer: w.peer}
	h.bus.mu.Unlock()
	t.Cleanup(func() {
		w.unhold()
		w.peer.Close(nil)
		h.bus.mu.Lock()
		h.bus.current[f.id] = original
		h.bus.mu.Unlock()
	})
	return w
}

// occupy holds the connection's writer with a line of the test's own, as a write to a manager that does not read
// does; done gets its result once the writer is let go.
func (w *agentHeldBus) occupy(t *testing.T) (done chan error) {
	t.Helper()
	done = make(chan error, 1)
	go func() { done <- w.peer.Notify(string(w.hold), map[string]any{}) }()
	select {
	case <-w.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("the writer was not taken")
	}
	return done
}

// agentWaitsForWriter waits until the event of k has been taken out of the backlog, has passed its check and waits
// for the held connection's writer, with nothing of it written.
func agentWaitsForWriter(t *testing.T, h *Hub, w *agentHeldBus, k agentKey) {
	t.Helper()
	q := &h.agentChanges
	eventually(t, 5*time.Second, "the event to wait for the connection's writer", func() bool {
		q.mu.Lock()
		defer q.mu.Unlock()
		return q.stop != nil && q.sending == k && w.peer.WriteWaiters() > 0
	})
	if got := w.written(); got != "" {
		t.Fatalf("something reached the transport while its writer was held: %s", got)
	}
}

// agentSendOver waits until nothing is being sent or waits to be: the drain ended, and no send waits for the writer.
func agentSendOver(t *testing.T, h *Hub, w *agentHeldBus, what string) {
	t.Helper()
	q := &h.agentChanges
	eventually(t, 5*time.Second, what, func() bool {
		q.mu.Lock()
		defer q.mu.Unlock()
		return !q.running && len(q.pending) == 0 && q.stop == nil && w.peer.WriteWaiters() == 0
	})
}

func agentEventsIn(written string) (lines []string) {
	for _, l := range strings.Split(written, "\n") {
		if strings.Contains(l, `"event":"agent.changed"`) {
			lines = append(lines, l)
		}
	}
	return lines
}

// The boundary of what is taken back (agent_changed.go): an event the hub has not begun to write is withdrawn. Here
// the event was taken out of the backlog and passed its check, and waits for the connection's writer behind another
// line; its session then becomes a space's conversation, or leaves the list. Its send gives up while the writer is
// still held, and nothing of it reaches the transport once the writer is free. The connection itself still works: a
// later change of a session the extensions are shown is written.
func TestAgentChangedWaitingForTheWriterIsWithdrawnOnceTheSessionIsHidden(t *testing.T) {
	for name, hide := range map[string]func(s3, s4 proto.Session) []proto.Session{
		"a space's conversation": func(s3, s4 proto.Session) []proto.Session { s3.Space = "health"; return []proto.Session{s3, s4} },
		"in a space's directory": func(s3, s4 proto.Session) []proto.Session {
			s3.Cwd = identity.HealthDir("/home/me")
			return []proto.Session{s3, s4}
		},
		"gone from the list": func(_, s4 proto.Session) []proto.Session { return []proto.Session{s4} },
	} {
		t.Run(name, func(t *testing.T) {
			h, a, _ := pathsHub(t, quiet, quiet)
			ear := agentEarOf(h, a)
			s3, s4 := proto.Session{SessionID: "s3", Status: proto.StatusIdle}, proto.Session{SessionID: "s4", Status: proto.StatusIdle}
			maSessions(h, s3, s4)
			ear.next(t)

			w := holdBus(t, h, a, "test.writer-held")
			held := w.occupy(t)
			s3.Status = proto.StatusWorking
			maSessions(h, s3, s4)
			agentWaitsForWriter(t, h, w, agentKey{a.id, "ma", "s3"})

			maSessions(h, hide(s3, s4)...)
			if !h.sessionHiddenFromExtensions("ma", "s3") {
				t.Fatal("the session is not hidden")
			}
			agentSendOver(t, h, w, "the send to give up while the writer is still held")
			if got := w.written(); got != "" {
				t.Fatalf("the transport got %s", got)
			}
			w.unhold()
			if err := <-held; err != nil {
				t.Fatal(err)
			}
			s4.Status = proto.StatusWorking
			maSessions(h, hide(s3, s4)...)
			eventually(t, 5*time.Second, "the next event on the same connection", func() bool { return len(agentEventsIn(w.written())) > 0 })
			agentSendOver(t, h, w, "the sends to end")
			want := `{"event":"extensions.emit","params":{"event":"agent.changed","params":{"machine_id":"ma","session_id":"s4"}}}`
			if got := agentEventsIn(w.written()); !slices.Equal(got, []string{want}) {
				t.Fatalf("the transport got %q, want only %s", got, want)
			}
		})
	}
}

// The same boundary when the computer goes to another manager: the event noted for the manager it leaves waits for
// that manager's writer, the computer moves, and nothing of the event reaches the manager it left. (A manager whose
// connection has stalled is, for the move, one that cannot be asked: the hub's roster no longer has it, while the
// send under way still holds the connection it was given.)
func TestAgentChangedWaitingForTheWriterIsWithdrawnOnceTheComputerMoves(t *testing.T) {
	h, a, b := pathsHub(t, quiet, quiet)
	earA, earB := agentEarOf(h, a), agentEarOf(h, b)
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusIdle})
	earA.next(t)
	earB.next(t)

	w := holdBus(t, h, a, "test.writer-held")
	held := w.occupy(t)
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusWorking})
	agentWaitsForWriter(t, h, w, agentKey{a.id, "ma", "s3"})

	h.bus.mu.Lock()
	stalled := h.bus.current[a.id]
	delete(h.bus.current, a.id)
	h.bus.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if _, err := h.moveMachine(ctx, "ma", b.id); err != nil {
		t.Fatal(err)
	}
	if h.machineOwner("ma") != b.id {
		t.Fatal("the computer did not move")
	}
	agentSendOver(t, h, w, "the send to give up while the writer is still held")
	if got := w.written(); got != "" {
		t.Fatalf("the transport of the manager the computer left got %s", got)
	}
	h.bus.mu.Lock()
	h.bus.current[a.id] = stalled
	h.bus.mu.Unlock()
	w.unhold()
	if err := <-held; err != nil {
		t.Fatal(err)
	}
	// What changes on the computer now is B's extensions' to hear; A's connection, working again, gets none of it.
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusIdle, State: proto.StateUnread})
	if got := earB.next(t); len(got) == 0 || slices.ContainsFunc(got, func(p string) bool { return !strings.Contains(p, `"machine_id":"ma","session_id":"s3"`) }) {
		t.Fatalf("the manager the computer went to was sent %q", got)
	}
	agentSendOver(t, h, w, "the sends to end")
	if got := agentEventsIn(w.written()); len(got) != 0 {
		t.Fatalf("the transport of the manager the computer left got %q", got)
	}
}

// The other side of the boundary: an event the hub has begun to write is delivered. Its line is in the transport's
// Write when its session becomes a space's conversation; the line is written to its end. The change it reports
// happened while the session was still shown.
func TestAgentChangedBegunIsDelivered(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusIdle})
	ear.next(t)

	w := holdBus(t, h, a, `"agent.changed"`)
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusWorking})
	select {
	case <-w.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("the event's line was not written")
	}
	maSessions(h, proto.Session{SessionID: "s3", Status: proto.StatusWorking, Space: "health"})
	if !h.sessionHiddenFromExtensions("ma", "s3") {
		t.Fatal("the session is not hidden")
	}
	w.unhold()
	agentSendOver(t, h, w, "the send to end")
	want := `{"event":"extensions.emit","params":{"event":"agent.changed","params":{"machine_id":"ma","session_id":"s3"}}}`
	if got := agentEventsIn(w.written()); !slices.Equal(got, []string{want}) {
		t.Fatalf("the transport got %q, want %s", got, want)
	}
}

// What the boundary above rests on: the bus between the hub and a manager is never framed (rpc lanes), so a line
// waits for one writer and is checked against its context when the writer is free (rpc.Peer.NotifyContext). A
// manager that asks for lanes is refused. If the bus is ever framed, the withdrawal of a line already queued for the
// frame writer has to be looked at again (agent_changed.go).
func TestTheManagerBusIsNotFramed(t *testing.T) {
	h, srv, _ := newBusHub(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ws, err := bus.Dial(ctx, srv.URL, busToken(t, h, assistantwire.AssistantID, assistantwire.AssistantID), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ws.CloseNow() })
	conn := bus.NewConn(ctx, ws)
	peer := rpc.New("g", conn)
	peer.EnableLanes(nil, nil)
	go func() { _ = peer.Serve(ctx, conn) }()
	cctx, cc := context.WithTimeout(ctx, 5*time.Second)
	defer cc()
	if err := peer.Call(cctx, busHello, map[string]any{"id": assistantwire.AssistantID}, nil); err != nil {
		t.Fatal(err)
	}
	if err := peer.UpgradeLanes(cctx); err == nil {
		t.Fatal("the hub framed its bus connection to a manager: agent.changed's withdrawal assumes one writer a line")
	}
	if c := h.busConnOf(assistantwire.AssistantID); c == nil {
		t.Fatal("the manager is not connected")
	} else if err := c.peer.NotifyContext(cctx, "test.after-refusal", map[string]any{}); err != nil {
		t.Fatalf("the connection after the refusal: %v", err)
	}
}

// The backlog holds one event a session: changes of a session that is waiting to be sent are folded into the one
// waiting, its ended or-ed, and the sessions go out in the order they first changed.
func TestAgentChangedBacklogIsOneASession(t *testing.T) {
	var q agentChanges
	s1, s2 := agentKey{"m", "ma", "s1"}, agentKey{"m", "ma", "s2"}
	var sent []agentChanged
	sending, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	send := func(k agentKey, ended bool) {
		if len(sent) == 0 {
			close(sending)
			<-release // the bus is held: what changes meanwhile waits
		}
		sent = append(sent, agentChanged{MachineID: k.machine, SessionID: k.session, Ended: ended})
	}
	if !q.mark(s1, false) {
		t.Fatal("the first change starts no drain")
	}
	go func() { q.drain(send); close(done) }()
	<-sending
	for _, c := range []struct {
		k     agentKey
		ended bool
	}{{s1, false}, {s2, false}, {s1, true}, {s1, false}, {s2, false}} {
		if q.mark(c.k, c.ended) {
			t.Fatal("a second drain was started while one runs")
		}
	}
	q.mu.Lock()
	if len(q.pending) != 2 || !slices.Equal(q.order, []agentKey{s1, s2}) || !q.pending[s1] || q.pending[s2] {
		t.Fatalf("the backlog: %v in order %v, want s1 (ended) then s2", q.pending, q.order)
	}
	q.mu.Unlock()
	close(release)
	<-done
	want := []agentChanged{{MachineID: "ma", SessionID: "s1"}, {MachineID: "ma", SessionID: "s1", Ended: true}, {MachineID: "ma", SessionID: "s2"}}
	if !slices.Equal(sent, want) {
		t.Fatalf("sent %+v, want %+v", sent, want)
	}
	if q.running || len(q.pending) != 0 || len(q.order) != 0 {
		t.Fatalf("the drain left %v %v behind", q.pending, q.order)
	}
	if !q.mark(s1, false) {
		t.Fatal("a change after the drain ended starts no drain")
	}
}

// The same through the hub: while the extensions cannot be told, a session's row, its card and its Stop hook are one
// event waiting, said as a turn's end, beside another session's.
func TestAgentChangedFoldsEverySourceIntoOne(t *testing.T) {
	h, a, _ := pathsHub(t, quiet, quiet)
	ear := agentEarOf(h, a)
	s3 := proto.Session{SessionID: "s3", Status: proto.StatusWorking, State: proto.StateWorking}
	s4 := proto.Session{SessionID: "s4", Status: proto.StatusWorking, State: proto.StateWorking}
	maSessions(h, s3, s4)
	ear.next(t)

	q := &h.agentChanges
	q.mu.Lock()
	q.running = true // as if a send were under way: nothing drains
	q.mu.Unlock()
	s4.PendingInteractions = 1
	maSessions(h, s3, s4)
	tellCard(t, h, a, proto.Interaction{ID: "ix_1", MachineID: "ma", SessionID: "s3", Cwd: "/work", Kind: proto.IxPermission})
	s3.PendingInteractions, s3.State = 1, proto.StateNeedsYou
	maSessions(h, s3, s4)
	stopHook(h, "ma", "s3")
	endCard(t, h, a, "ix_1")
	s3.PendingInteractions, s3.Status, s3.State = 0, proto.StatusIdle, proto.StateUnread
	maSessions(h, s3, s4)
	q.mu.Lock()
	if s3, s4 := (agentKey{assistantwire.AssistantID, "ma", "s3"}), (agentKey{assistantwire.AssistantID, "ma", "s4"}); len(q.pending) != 2 || !slices.Equal(q.order, []agentKey{s4, s3}) || !q.pending[s3] {
		t.Fatalf("the backlog: %v in order %v, want s4 then s3 (ended)", q.pending, q.order)
	}
	q.mu.Unlock()
	go q.drain(h.sendAgentChanged)
	ear.want(t, "the backlog", `{"machine_id":"ma","session_id":"s4"}`, s3Ended)
}
