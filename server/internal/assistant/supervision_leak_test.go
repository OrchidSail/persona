package assistant

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/conversationlog"
	"github.com/nwverse/persona/server/internal/db"
	"github.com/nwverse/persona/server/internal/prompts"
	"github.com/nwverse/persona/server/internal/proto"
)

type fakeLogicalStore struct {
	*memStore
	conv     *db.LogicalConversation
	receipts map[string]string
	enqueued map[string]json.RawMessage
}

func (f *fakeLogicalStore) LogicalConversation(_ context.Context, machine, id string) (*db.LogicalConversation, error) {
	if f.conv != nil && f.conv.MachineID == machine && f.conv.SessionID == id {
		cp := *f.conv
		return &cp, nil
	}
	return nil, nil
}
func (f *fakeLogicalStore) LogicalConversations(_ context.Context) ([]db.LogicalConversation, error) {
	if f.conv == nil {
		return nil, nil
	}
	return []db.LogicalConversation{*f.conv}, nil
}
func (f *fakeLogicalStore) AdoptConversationAliases(context.Context, string, string, string, string, string, []string) error {
	return nil
}
func (f *fakeLogicalStore) WithConversationEpoch(_ context.Context, _, _ string, _ int64, fn func(*db.LogicalConversation) error) error {
	cp := *f.conv
	return fn(&cp)
}
func (f *fakeLogicalStore) WithDrainedConversationEpoch(ctx context.Context, m, id string, epoch int64, fn func(*db.LogicalConversation) error) error {
	return f.WithConversationEpoch(ctx, m, id, epoch, fn)
}
func (f *fakeLogicalStore) BeginConversationSwitch(context.Context, string, string, db.ConversationSwitch) error {
	return nil
}
func (f *fakeLogicalStore) PrepareConversationSegment(context.Context, string, string, string, conversationlog.Segment, int) error {
	return nil
}
func (f *fakeLogicalStore) ActivateConversationSegment(context.Context, string, string, string, int64) error {
	return nil
}
func (f *fakeLogicalStore) ResolveConversationSwitch(context.Context, string, string, string, string, string) error {
	return nil
}
func (f *fakeLogicalStore) ConversationSwitch(context.Context, string, string, string) (*db.ConversationSwitch, error) {
	return nil, nil
}
func (f *fakeLogicalStore) ObserveConversationSource(context.Context, string, string, string) error {
	return nil
}
func (f *fakeLogicalStore) ObserveConversationRevision(context.Context, string, string, int64) error {
	return nil
}
func (f *fakeLogicalStore) ConversationSegmentClosed(context.Context, string) error { return nil }
func (f *fakeLogicalStore) EnqueueConversationInput(_ context.Context, _, _, client string, _ int64, body json.RawMessage) error {
	if f.enqueued == nil {
		f.enqueued = map[string]json.RawMessage{}
	}
	f.enqueued[client] = append(json.RawMessage(nil), body...)
	return nil
}
func (f *fakeLogicalStore) ConversationInputReceipt(_ context.Context, _, _, client string, _ json.RawMessage) (string, error) {
	return f.receipts[client], nil
}
func (f *fakeLogicalStore) ClaimConversationInput(context.Context, string, string, int64) (*db.ConversationInput, error) {
	return nil, nil
}
func (f *fakeLogicalStore) ResolveConversationInput(context.Context, string, string, string, string) error {
	return nil
}

func sampleSuperviseDigest(level string, withCompactMention bool) string {
	last := "Finished checks and summarized results."
	if withCompactMention {
		last = "Reviewed branch feat-auto-compact and slash /compact behavior."
	}
	return prompts.RenderSupervise(prompts.Supervise{
		Level: level,
		Entries: []prompts.SuperviseEntry{
			{ID: "todo_alpha", Title: "Alpha task", Kind: "review", Machine: "server", Session: "agy-aaaa", Last: last},
			{ID: "todo_beta", Title: "Beta task", Kind: "idle", Machine: "server", Session: "agy-bbbb", Detail: "Waiting for triage."},
		},
	})
}

func TestLogicalSupervisionRelayOmitsClientMsgID(t *testing.T) {
	st := &fakeLogicalStore{
		memStore: &memStore{},
		conv: &db.LogicalConversation{
			MachineID: "server",
			SessionID: "side-chat",
			Epoch:     2,
			Segments: []conversationlog.Segment{
				{ID: "one", Ordinal: 1, MachineID: "server", SessionID: "side-chat", Engine: "claude", Launcher: "claude", State: "retired"},
				{ID: "two", Ordinal: 2, MachineID: "server", SessionID: "target-chat", Engine: "agy", Launcher: "agy", State: "active"},
			},
		},
	}
	s, f := newQuietServiceOn(t, st)
	ctx := t.Context()

	var dispatched map[string]any
	f.mu.Lock()
	f.known["target-chat"] = true
	f.onAssistantSend = func(_ *fakeHub, p map[string]any) {
		dispatched = p
	}
	f.mu.Unlock()

	digest := sampleSuperviseDigest("triage", true)
	msg := relayMsg{Tool: superviseTool, Text: digest, At: time.Now().UTC()}
	if err := s.deliverRelay(ctx, "server", "side-chat", msg); err != nil {
		t.Fatalf("deliverRelay: %v", err)
	}
	if dispatched == nil {
		t.Fatal("supervision relay did not reach the active physical segment")
	}
	if dispatched["session_id"] != "target-chat" {
		t.Fatalf("dispatched session_id = %v, want target-chat", dispatched["session_id"])
	}
	if cid, ok := dispatched["client_msg_id"]; ok && cid != "" {
		t.Fatalf("supervision relay attached client_msg_id = %v; synthetic segment ids turn supervision into ordinary user bubbles", cid)
	}
	if once, _ := dispatched["once_id"].(string); once == "" {
		t.Fatal("supervision relay dropped once_id deduplication key")
	}
	text, _ := dispatched["text"].(string)
	item := proto.Item{Role: "user", Kind: proto.KindText, Text: text}
	if src := prompts.ItemSource(item); src == nil || src.Kind != proto.SourceSupervision {
		t.Fatalf("ItemSource(%q) = %+v, want supervision", text, src)
	}
	if in := prompts.ParseInjected(text); in == nil || in.State != "supervise" || in.Level != "triage" {
		t.Fatalf("ParseInjected = %+v, want supervise/triage", in)
	}

	// Queued internal sends during a switch must use once_id (never client_msg_id).
	st.conv.Switch = &db.ConversationSwitch{OperationID: "op-1", ExpectedEpoch: 2, SourceSegment: "two"}
	rawReq, _ := json.Marshal(map[string]any{"session_id": "side-chat", "text": digest})
	if _, handled, err := s.logicalCall(ctx, "server", DeviceAssistantSend, rawReq); err != nil || !handled {
		t.Fatalf("queued logicalCall: handled=%v err=%v", handled, err)
	}
	if len(st.enqueued) != 1 {
		t.Fatalf("enqueued inputs = %d, want 1", len(st.enqueued))
	}
	for key, payload := range st.enqueued {
		if !strings.HasPrefix(key, "segment-input:") {
			t.Fatalf("queued receipt key = %q, want segment-input: prefix", key)
		}
		var queued logicalInput
		if err := json.Unmarshal(payload, &queued); err != nil {
			t.Fatal(err)
		}
		if _, has := queued.Params["client_msg_id"]; has {
			t.Fatalf("queued internal input set client_msg_id: %+v", queued.Params)
		}
		if queued.Params["once_id"] != key {
			t.Fatalf("queued internal input once_id = %v, want %q", queued.Params["once_id"], key)
		}
	}

	// Pre-upgrade queued payloads carrying synthetic segment ids in client_msg_id are stripped before physical dispatch.
	seg := conversationlog.Segment{SessionID: "target-chat"}
	cleanedOnce := cloneLogicalParams(map[string]any{"session_id": "side-chat", "client_msg_id": "segment-once:abc123", "text": digest}, seg)
	if _, has := cleanedOnce["client_msg_id"]; has || cleanedOnce["once_id"] != "abc123" {
		t.Fatalf("cloneLogicalParams(segment-once) = %+v", cleanedOnce)
	}
	cleanedInput := cloneLogicalParams(map[string]any{"session_id": "side-chat", "client_msg_id": "segment-input:def456", "text": digest}, seg)
	if _, has := cleanedInput["client_msg_id"]; has || cleanedInput["once_id"] != "segment-input:def456" {
		t.Fatalf("cloneLogicalParams(segment-input) = %+v", cleanedInput)
	}
}

func TestRelayArrivedMatchesAgyTrailingCompactEcho(t *testing.T) {
	digest := sampleSuperviseDigest("triage", true)
	msg := relayMsg{Tool: superviseTool, Text: digest, At: time.Now().UTC(), TriedAt: time.Now().UTC().Add(-time.Second)}

	s, f := newQuietService(t)
	f.setItems(proto.Item{
		Seq:  1,
		Role: "user",
		Kind: proto.KindText,
		Text: digest + "\n/compact",
		TS:   time.Now().UTC(),
	})
	if ok, err := s.relayArrived("server", "conv1", msg); err != nil || !ok {
		t.Fatalf("relayArrived missed supervision digest with spurious trailing agy /compact: ok=%v err=%v", ok, err)
	}

	// Without an earlier /compact mention in the prompt, an unrelated trailing /compact is not treated as an echo.
	plain := sampleSuperviseDigest("triage", false)
	plainMsg := relayMsg{Tool: superviseTool, Text: plain, At: time.Now().UTC(), TriedAt: time.Now().UTC().Add(-time.Second)}
	f.setItems(proto.Item{
		Seq:  2,
		Role: "user",
		Kind: proto.KindText,
		Text: plain + "\n/compact",
		TS:   time.Now().UTC(),
	})
	if ok, err := s.relayArrived("server", "conv1", plainMsg); err != nil || ok {
		t.Fatalf("relayArrived falsely matched trailing /compact when prompt never mentioned /compact: ok=%v err=%v", ok, err)
	}
}

func TestLateConfirmedSupervisionRelayRestoresHoldAndAllowsSilentFinish(t *testing.T) {
	s, f := newQuietService(t)
	ctx := t.Context()
	digest := sampleSuperviseDigest("triage", true)
	sentAt := time.Now().UTC()
	msg := relayMsg{Tool: superviseTool, Text: digest, At: sentAt, TriedAt: sentAt}

	// Simulate direct agy send whose transcript arrived only after the immediate confirmation window closed.
	f.setItems(proto.Item{
		Seq:  1,
		Role: "user",
		Kind: proto.KindText,
		Text: digest + "\n/compact",
		TS:   sentAt.Add(time.Second),
	})
	if s.supervising("conv1") {
		t.Fatal("expected hold to be clear before late transcript confirmation")
	}
	if ok, err := s.relayArrived("server", "conv1", msg); err != nil || !ok {
		t.Fatalf("expected late-confirmed transcript item to match: ok=%v err=%v", ok, err)
	}
	s.relayDelivered(ctx, "conv1", msg)
	if !s.supervising("conv1") {
		t.Fatal("late confirmation of supervision relay did not restore push hold")
	}
	out, err := s.toolSupervisionFinishOn(ctx, "server", "conv1", json.RawMessage(`{"visibility":"silent","summary":"Handled after late export."}`))
	if err != nil || !strings.Contains(out, `"visibility":"silent"`) {
		t.Fatalf("supervision_finish(silent) after late confirmation = %q, %v", out, err)
	}
}

func TestSupervisionFinishSilentWhenOwnerQueuedMessageMidTurn(t *testing.T) {
	ctx := t.Context()
	s, f, main := preemptService(t)
	digest := sampleSuperviseDigest("triage", false)
	if err := s.deliverRelay(ctx, "server", main, relayMsg{Tool: superviseTool, Text: digest}); err != nil {
		t.Fatal(err)
	}
	seeStatus(s, f, main, proto.StatusWorking)

	// Owner sends a message while the supervision turn is still running (queued on agy / interjecting).
	s.UserSpoke(ctx, SessionRef{MachineID: "server", SessionID: main})
	if s.supervising(main) {
		t.Fatal("owner message did not release supervision push hold")
	}

	// Before the transcript even flushes, the still-running supervision turn may still finish silently.
	if out, err := s.toolSupervisionFinishOn(ctx, "server", main, json.RawMessage(`{"visibility":"silent","summary":"Triaged before queued owner message."}`)); err != nil || !strings.Contains(out, `"visibility":"silent"`) {
		t.Fatalf("supervision_finish(silent) while owner message queued = %q, %v", out, err)
	}

	// Promoting the preempted supervision turn blocks silent finish.
	s.promoteSupervision(main)
	if _, err := s.toolSupervisionFinishOn(ctx, "server", main, json.RawMessage(`{"visibility":"silent","summary":"Too late."}`)); err == nil {
		t.Fatal("promoted preempted supervision turn still allowed visibility=silent")
	}

	// Once a real owner message is the latest transcript input, silent finish is refused even while working.
	s2, f2, main2 := preemptService(t)
	if err := s2.deliverRelay(ctx, "server", main2, relayMsg{Tool: superviseTool, Text: digest}); err != nil {
		t.Fatal(err)
	}
	seeStatus(s2, f2, main2, proto.StatusWorking)
	s2.UserSpoke(ctx, SessionRef{MachineID: "server", SessionID: main2})
	f2.mu.Lock()
	f2.sentItems = nil
	f2.mu.Unlock()
	f2.setItems(
		proto.Item{Seq: 1, Role: "user", Kind: proto.KindText, Text: digest, TS: time.Now().Add(-time.Second)},
		proto.Item{Seq: 2, Role: "user", Kind: proto.KindText, ClientMsgID: "app-user-1", Text: "Checking in", TS: time.Now()},
	)
	if _, err := s2.toolSupervisionFinishOn(ctx, "server", main2, json.RawMessage(`{"visibility":"silent","summary":"Must refuse."}`)); err == nil {
		t.Fatal("normal user turn in transcript allowed visibility=silent after preempt")
	}

	// Once the supervision round ends (idle), preempted state is cleared and even a stale transcript
	// item without turn_duration (as on agy) cannot authorize silent finish after the owner spoke.
	s3, f3, main3 := preemptService(t)
	if err := s3.deliverRelay(ctx, "server", main3, relayMsg{Tool: superviseTool, Text: digest}); err != nil {
		t.Fatal(err)
	}
	seeStatus(s3, f3, main3, proto.StatusWorking)
	s3.UserSpoke(ctx, SessionRef{MachineID: "server", SessionID: main3})
	seeStatus(s3, f3, main3, proto.StatusIdle)
	if _, err := s3.toolSupervisionFinishOn(ctx, "server", main3, json.RawMessage(`{"visibility":"silent","summary":"After idle."}`)); err == nil {
		t.Fatal("ended preempted supervision turn still allowed visibility=silent")
	}
}

func TestSupervisionFinishSilentFallsBackToLiveTranscript(t *testing.T) {
	ctx := context.Background()
	triageDigest := sampleSuperviseDigest("triage", true)
	userDigest := sampleSuperviseDigest("user", false)

	cases := []struct {
		name    string
		items   []proto.Item
		wantErr bool
	}{
		{
			name: "open multi-signal triage supervision round after hub restart",
			items: []proto.Item{
				{Seq: 1, Role: "user", Kind: proto.KindText, ClientMsgID: "owner-1", Text: "Old owner turn"},
				{Seq: 2, Role: "assistant", Kind: proto.KindText, Text: "Old reply"},
				{Seq: 3, Role: "user", Kind: proto.KindText, Text: triageDigest},
				{Seq: 4, Role: "assistant", Kind: proto.KindThinking, Text: "Checking both tasks."},
			},
			wantErr: false,
		},
		{
			name: "already promoted via notify_user refuses silent",
			items: []proto.Item{
				{Seq: 1, Role: "user", Kind: proto.KindText, Text: triageDigest},
				{Seq: 2, Role: "assistant", Kind: proto.KindToolUse, ToolUseID: "n1", ToolName: "mcp__persona__notify_user"},
				{Seq: 3, Role: "user", Kind: proto.KindToolResult, ToolUseID: "n1", Text: "Sent."},
			},
			wantErr: true,
		},
		{
			name: "user-level supervision digest refuses silent",
			items: []proto.Item{
				{Seq: 1, Role: "user", Kind: proto.KindText, Text: userDigest},
			},
			wantErr: true,
		},
		{
			name: "closed supervision round refuses silent",
			items: []proto.Item{
				{Seq: 1, Role: "user", Kind: proto.KindText, Text: triageDigest},
				{Seq: 2, Kind: "turn_duration"},
			},
			wantErr: true,
		},
		{
			name: "normal user message refuses silent",
			items: []proto.Item{
				{Seq: 1, Role: "user", Kind: proto.KindText, ClientMsgID: "app-msg", Text: "Hello"},
			},
			wantErr: true,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, f := newQuietService(t)
			f.setItems(tc.items...)
			out, err := s.toolSupervisionFinishOn(ctx, "server", "conv1", json.RawMessage(`{"visibility":"silent","summary":"Checked."}`))
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected refusal, got %q", out)
				}
				return
			}
			if err != nil || !strings.Contains(out, `"visibility":"silent"`) {
				t.Fatalf("unexpected error = %v, out = %q", err, out)
			}
		})
	}

	t.Run("side supervisor triage turn after noteSideSupervisionPush allows silent and clears side links", func(t *testing.T) {
		s, f := newQuietService(t)
		s.noteSideSupervisionPush(ctx, "side-dev", relayMsg{Tool: superviseTool, Text: triageDigest})
		f.setItems(proto.Item{Seq: 1, Role: "user", Kind: proto.KindText, Text: triageDigest, TS: time.Now()})
		out, err := s.toolSupervisionFinishOn(ctx, "server", "side-dev", json.RawMessage(`{"visibility":"silent","summary":"Side supervisor triaged quietly."}`))
		if err != nil || !strings.Contains(out, `"visibility":"silent"`) {
			t.Fatalf("side supervisor silent finish = %q, %v", out, err)
		}
		if links := s.consumeSideSupervisionLinks("side-dev"); len(links) != 0 {
			t.Fatalf("silent finish left side push links behind: %v", links)
		}
	})
}
