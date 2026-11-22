package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/assistant/chatsearch"
	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/proto"
)

func requestSendParams(t *testing.T, s *Service, machine, id string, params map[string]any) (map[string]any, error) {
	t.Helper()
	b := &BusHub{svc: s, helloed: make(chan struct{})}
	close(b.helloed)
	raw, err := json.Marshal(assistantwire.SendParams{
		MachineID: machine,
		SessionID: id,
		Params:    params,
		Human:     true,
	})
	if err != nil {
		t.Fatal(err)
	}
	out, err := b.request(context.Background(), assistantwire.MethodSendParams, raw)
	if err != nil {
		return nil, err
	}
	m, _ := out.(map[string]any)
	got, _ := m["params"].(map[string]any)
	return got, nil
}

func snapshotQueuedFor(snap Snapshot, id string) []string {
	for _, sv := range snap.Sessions {
		if sv.SessionID == id {
			return sv.Queued
		}
	}
	return nil
}

// Sending a user message to a managed assistant conversation carries queued wake:false relays in front as a
// <persona-context> block, consumes them only once the node accepts the message, and publishes a snapshot that clears
// queuedRelays without starting any extra manager turn.
func TestOwnerSendCarriesAndDrainsQuietRelay(t *testing.T) {
	s, f := newQuietService(t)
	f.known["conv2"] = true
	f.setSessions("server",
		proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
		proto.Session{SessionID: "conv2", MachineID: "server", Assistant: true, Status: proto.StatusIdle, Title: "Release Lead"},
	)
	ctx := context.Background()
	if _, err := s.toolConversationSend(ctx, "conv2", []byte(`{"id":"conv1","text":"Quiet sync: PR is green.","wake":false}`)); err != nil {
		t.Fatal(err)
	}
	if labels, ok := s.queuedRelays(); !ok || len(labels["conv1"]) != 1 || !strings.Contains(labels["conv1"][0], "Quiet sync: PR is green.") {
		t.Fatalf("queuedRelays before owner send = %v, %v", labels, ok)
	}
	if got := snapshotQueuedFor(f.lastSnapshot(), "conv1"); len(got) != 1 {
		t.Fatalf("snapshot queued before owner send = %v, want 1", got)
	}

	sent, err := requestSendParams(t, s, "server", "conv1", map[string]any{
		"text":          "Check the latest status.",
		"client_msg_id": "owner-msg-1",
	})
	if err != nil {
		t.Fatalf("MethodSendParams: %v", err)
	}
	payload, _ := sent["text"].(string)
	if !strings.Contains(payload, `<persona-context kind="context" title="没有叫醒你的消息" url="">`) ||
		!strings.Contains(payload, "Quiet sync: PR is green.") ||
		!strings.HasSuffix(payload, "\nCheck the latest status.") {
		t.Fatalf("owner send payload did not prepend quiet relay context:\n%s", payload)
	}
	// User-visible body extraction and chat search keep only what the user typed, never raw <persona-context> markup.
	if got := strings.TrimSpace(stripContext(payload)); got != "Check the latest status." {
		t.Fatalf("stripContext = %q, want user text only", got)
	}
	if indexed, ok := chatsearch.IndexText(proto.Item{Role: "user", Kind: proto.KindText, ClientMsgID: "owner-msg-1", Text: payload}); !ok || indexed != "Check the latest status." {
		t.Fatalf("chatsearch.IndexText = %q, %v; want user text only", indexed, ok)
	}
	// Quiet context carries another conversation's words: secret hand-off Always rules must not bypass approval,
	// while missing-reply recovery still recognizes the owner's message.
	if s.humanSent.has("conv1", "owner-msg-1", payload, false, "line-1") {
		t.Fatal("quiet relay carry must downgrade humanSent to reply-only")
	}
	if !s.humanSent.claimReply("conv1", proto.Item{ID: "line-1#0", ClientMsgID: "owner-msg-1", Text: payload}) {
		t.Fatal("missing-reply recovery stopped matching an owner message carrying quiet context")
	}

	// Not consumed before the node accepts the message.
	if n := len(laterFor(s, "conv1")); n != 1 {
		t.Fatalf("quiet relay dropped before ConversationDelivered (%d left)", n)
	}

	f.mu.Lock()
	snapsBefore := len(f.snaps)
	f.mu.Unlock()

	s.ConversationSent(ctx, ConversationSent{
		MachineID:   "server",
		SessionID:   "conv1",
		ClientMsgID: "owner-msg-1",
		Outcome:     assistantwire.ConversationDelivered,
	})

	if n := len(laterFor(s, "conv1")); n != 0 {
		t.Fatalf("quiet relay still waiting after ConversationDelivered: %d", n)
	}
	if labels, ok := s.queuedRelays(); !ok || len(labels["conv1"]) != 0 {
		t.Fatalf("queuedRelays after delivery = %v, %v; want empty", labels, ok)
	}
	f.mu.Lock()
	snapsAfter := len(f.snaps)
	f.mu.Unlock()
	if snapsAfter <= snapsBefore {
		t.Fatalf("expected snapshot publish after draining quiet relay (before=%d after=%d)", snapsBefore, snapsAfter)
	}
	if got := snapshotQueuedFor(f.lastSnapshot(), "conv1"); len(got) != 0 {
		t.Fatalf("snapshot queued after delivery = %v, want empty", got)
	}
	if n := f.sentTo("conv1"); n != 0 {
		t.Fatalf("manager started %d extra turn(s), want 0", n)
	}
}

// Failed, unknown, refused, and slash-command owner sends must never drop queued wake:false messages.
func TestOwnerSendFailureAndCommandsKeepQuietRelay(t *testing.T) {
	s, f := newQuietService(t)
	f.known["conv2"] = true
	f.setSessions("server",
		proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
		proto.Session{SessionID: "conv2", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
	)
	ctx := context.Background()
	if _, err := s.toolConversationSend(ctx, "conv2", []byte(`{"id":"conv1","text":"Keep me safe.","wake":false}`)); err != nil {
		t.Fatal(err)
	}

	// Failed send keeps Later.
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "First try", "client_msg_id": "fail-1"}); err != nil {
		t.Fatal(err)
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "fail-1", Outcome: assistantwire.ConversationFailed})
	if n := len(laterFor(s, "conv1")); n != 1 {
		t.Fatalf("failed send dropped quiet relay (%d left)", n)
	}

	// Unknown outcome keeps Later (at worst read twice, never lost).
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Second try", "client_msg_id": "unknown-1"}); err != nil {
		t.Fatal(err)
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "unknown-1", Outcome: assistantwire.ConversationUnknown})
	if n := len(laterFor(s, "conv1")); n != 1 {
		t.Fatalf("unknown outcome dropped quiet relay (%d left)", n)
	}

	// Slash command carries nothing and clears nothing.
	cmd, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "/compact", "client_msg_id": "cmd-1"})
	if err != nil {
		t.Fatal(err)
	}
	if cmd["text"] != "/compact" {
		t.Fatalf("slash command carried quiet relay: %q", cmd["text"])
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "cmd-1", Outcome: assistantwire.ConversationDelivered})
	if n := len(laterFor(s, "conv1")); n != 1 {
		t.Fatalf("slash command dropped quiet relay (%d left)", n)
	}

	// Refused SendParams leaves Later untouched.
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Bad model", "model": "not a model id!", "client_msg_id": "bad-1"}); err == nil {
		t.Fatal("expected invalid model refusal")
	}
	if n := len(laterFor(s, "conv1")); n != 1 {
		t.Fatalf("refused SendParams dropped quiet relay (%d left)", n)
	}
}

// Retrying an owner send under the same client_msg_id reuses the exact original reservation (even across manager
// restarts) and never consumes quiet messages that arrived after the first attempt.
func TestOwnerSendRetryIdempotentAndPreservesNewQuietRelays(t *testing.T) {
	for _, tc := range []struct {
		name           string
		initiallyEmpty bool
	}{
		{name: "with_initial_quiet_relay", initiallyEmpty: false},
		{name: "initially_empty", initiallyEmpty: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			st := &memStore{}
			s, f := newQuietServiceOn(t, st)
			f.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
			ctx := context.Background()

			if !tc.initiallyEmpty {
				if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Original quiet note.", At: time.Now().UTC()}); !ok {
					t.Fatal("could not keep original quiet note")
				}
			}

			first, err := requestSendParams(t, s, "server", "conv1", map[string]any{
				"text":          "Owner prompt",
				"client_msg_id": "owner-retry-1",
			})
			if err != nil {
				t.Fatalf("first MethodSendParams: %v", err)
			}
			firstText, _ := first["text"].(string)
			if tc.initiallyEmpty && strings.Contains(firstText, "<persona-context") {
				t.Fatalf("unexpected context on empty Later: %q", firstText)
			}
			if !tc.initiallyEmpty && !strings.Contains(firstText, "Original quiet note.") {
				t.Fatalf("first send missing original quiet note: %q", firstText)
			}

			// First attempt fails; a new quiet relay arrives before the retry.
			s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "owner-retry-1", Outcome: assistantwire.ConversationFailed})
			if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Arrived during retry.", At: time.Now().UTC()}); !ok {
				t.Fatal("could not keep new quiet note")
			}

			// Restart the manager between attempt 1 and attempt 2.
			again, fAgain := newQuietServiceOn(t, st)
			fAgain.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
			retry, err := requestSendParams(t, again, "server", "conv1", map[string]any{
				"text":          "Owner prompt",
				"client_msg_id": "owner-retry-1",
			})
			if err != nil {
				t.Fatalf("retry MethodSendParams: %v", err)
			}
			if retry["text"] != first["text"] {
				t.Fatalf("retry payload changed:\nfirst=%q\nretry=%q", first["text"], retry["text"])
			}

			// Restart again before session.assistant_sent arrives to verify durable settlement by client_msg_id.
			settled, fSettled := newQuietServiceOn(t, st)
			fSettled.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
			settled.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "owner-retry-1", Outcome: assistantwire.ConversationDelivered})

			left := laterFor(settled, "conv1")
			if len(left) != 1 || left[0].Text != "Arrived during retry." {
				t.Fatalf("retry consumed the wrong quiet messages: %+v", left)
			}

			// Next send with a fresh client_msg_id carries and drains the newly arrived quiet message.
			next, err := requestSendParams(t, settled, "server", "conv1", map[string]any{
				"text":          "Follow up",
				"client_msg_id": "owner-retry-2",
			})
			if err != nil {
				t.Fatalf("next MethodSendParams: %v", err)
			}
			nextText, _ := next["text"].(string)
			if !strings.Contains(nextText, "Arrived during retry.") || strings.Contains(nextText, "Original quiet note.") {
				t.Fatalf("next send payload = %q", nextText)
			}
			settled.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "owner-retry-2", Outcome: assistantwire.ConversationDelivered})
			if len(laterFor(settled, "conv1")) != 0 {
				t.Fatalf("next send did not clear remaining quiet message: %+v", laterFor(settled, "conv1"))
			}
			if labels, ok := settled.queuedRelays(); !ok || len(labels["conv1"]) != 0 {
				t.Fatalf("queuedRelays still non-empty: %v, %v", labels, ok)
			}
		})
	}
}

// Unnamed owner sends (no client_msg_id) pair FIFO with session.assistant_sent, and non-assistant Agent sessions
// never attach or consume quiet relays even if SendParams is invoked.
func TestOwnerSendWithoutClientMsgIDAndNonAssistantGuard(t *testing.T) {
	s, f := newQuietService(t)
	f.setSessions("server",
		proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
		proto.Session{SessionID: "agent1", MachineID: "server", Assistant: false, PaneID: "%1", Status: proto.StatusIdle},
	)
	ctx := context.Background()
	if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Unnamed delivery note.", At: time.Now().UTC()}); !ok {
		t.Fatal("could not keep conv1 note")
	}
	if _, ok := s.keepRelayLater("agent1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Must stay untouched.", At: time.Now().UTC()}); !ok {
		t.Fatal("could not keep agent1 note")
	}

	// Non-assistant Agent session is unaffected.
	agentParams, err := requestSendParams(t, s, "server", "agent1", map[string]any{"text": "Run tests", "client_msg_id": "agent-1"})
	if err != nil {
		t.Fatalf("agent MethodSendParams: %v", err)
	}
	if agentParams["text"] != "Run tests" {
		t.Fatalf("non-assistant session got quiet relay context: %q", agentParams["text"])
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "agent1", ClientMsgID: "agent-1", Outcome: assistantwire.ConversationDelivered})
	if len(laterFor(s, "agent1")) != 1 {
		t.Fatal("non-assistant session modified relayState.Later")
	}

	// Unnamed slash command followed by unnamed real message: command completion must not pop the real message's carry.
	cmd, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "/status"})
	if err != nil || cmd["text"] != "/status" {
		t.Fatalf("unnamed command: %+v, %v", cmd, err)
	}
	real, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Hello without id"})
	if err != nil || !strings.Contains(real["text"].(string), "Unnamed delivery note.") {
		t.Fatalf("unnamed real send: %+v, %v", real, err)
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", Outcome: assistantwire.ConversationDelivered})
	if len(laterFor(s, "conv1")) != 1 {
		t.Fatal("unnamed command completion consumed the next unnamed message's quiet relay")
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", Outcome: assistantwire.ConversationDelivered})
	if len(laterFor(s, "conv1")) != 0 {
		t.Fatal("unnamed real send completion did not drain quiet relay")
	}
}

// An owner send that failed, was kept in undelivered, and later succeeded via deliverKept (or landed detection)
// reuses its original client_msg_id reservation and clears only the reserved quiet relays.
func TestOwnerSendUndeliveredRetryAndLandedDrainQuietRelay(t *testing.T) {
	st := &memStore{}
	s, f := newQuietServiceOn(t, st)
	f.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
	ctx := context.Background()

	if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Before outage.", At: time.Now().UTC()}); !ok {
		t.Fatal("keep Q1")
	}
	// Hub prepared send "kept-1" (reserving Q1), node failed, hub reported ConversationFailed and kept raw message.
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Resume work", "client_msg_id": "kept-1"}); err != nil {
		t.Fatal(err)
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "kept-1", Outcome: assistantwire.ConversationFailed})
	if _, err := s.KeepUndelivered(ctx, assistantwire.KeepUndeliveredParams{
		MachineID: "server", SessionID: "conv1",
		Params: map[string]any{"text": "Resume work", "client_msg_id": "kept-1"},
		Human:  true, Code: "machine_offline",
	}); err != nil {
		t.Fatal(err)
	}

	// Q2 arrives while kept-1 waits.
	if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "During outage.", At: time.Now().UTC()}); !ok {
		t.Fatal("keep Q2")
	}

	// Automatic undelivered retry delivers kept-1 carrying only Q1.
	s.undeliveredPass(ctx, time.Now().Add(time.Hour))
	sentText := ""
	f.mu.Lock()
	for _, c := range f.calls {
		if c.Method != DeviceSessionSend {
			continue
		}
		var a map[string]any
		_ = json.Unmarshal(c.Params, &a)
		if a["client_msg_id"] == "kept-1" {
			sentText, _ = a["text"].(string)
		}
	}
	f.mu.Unlock()
	if !strings.Contains(sentText, "Before outage.") || strings.Contains(sentText, "During outage.") || !strings.HasSuffix(sentText, "\nResume work") {
		t.Fatalf("deliverKept payload = %q", sentText)
	}
	left := laterFor(s, "conv1")
	if len(left) != 1 || left[0].Text != "During outage." {
		t.Fatalf("after deliverKept, Later = %+v; want only Q2", left)
	}

	// Now simulate an unknown-outcome send ("kept-2") that actually landed in the transcript.
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Check landed", "client_msg_id": "kept-2"}); err != nil {
		t.Fatal(err)
	}
	s.ConversationSent(ctx, ConversationSent{MachineID: "server", SessionID: "conv1", ClientMsgID: "kept-2", Outcome: assistantwire.ConversationUnknown})
	if _, err := s.KeepUndelivered(ctx, assistantwire.KeepUndeliveredParams{
		MachineID: "server", SessionID: "conv1",
		Params: map[string]any{"text": "Check landed", "client_msg_id": "kept-2"},
		Human:  true, Code: assistantwire.CodeDeliveryUnknown, TriedAt: time.Now().Add(-time.Second),
	}); err != nil {
		t.Fatal(err)
	}
	f.setItems(proto.Item{ID: "u1#0", Seq: 1, Role: "user", Kind: proto.KindText, ClientMsgID: "kept-2", Text: "Check landed", TS: time.Now()})
	s.undeliveredPass(ctx, time.Now().Add(time.Hour))
	if len(laterFor(s, "conv1")) != 0 {
		t.Fatalf("landed check did not drain Q2: %+v", laterFor(s, "conv1"))
	}
}

// Storage failure while reserving quiet relay context stops before returning params to the hub.
func TestOwnerSendCarrySaveFailureRefusesBeforeNodeWrite(t *testing.T) {
	st := &memStore{}
	s, f := newQuietServiceOn(t, st)
	f.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
	if _, ok := s.keepRelayLater("conv1", relayMsg{From: "conv2", FromTitle: "Lead", Text: "Must reserve first.", At: time.Now().UTC()}); !ok {
		t.Fatal("keep")
	}
	st.setFailPut(errors.New("database unavailable"))
	if _, err := requestSendParams(t, s, "server", "conv1", map[string]any{"text": "Go", "client_msg_id": "fail-save-1"}); err == nil {
		t.Fatal("expected error when carry reservation could not be saved")
	}
	st.setFailPut(nil)
	if len(laterFor(s, "conv1")) != 1 {
		t.Fatal("failed reservation save lost quiet relay")
	}
}
