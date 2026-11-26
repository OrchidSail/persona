package hub

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/db"
	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

func sendSubscriber(h *Hub, mid, sid string) *client {
	c := appClient(h)
	c.mu.Lock()
	c.subs[[2]string{mid, sid}] = true
	c.mu.Unlock()
	return c
}

func nextSendState(t *testing.T, c *client) proto.MessageSendState {
	t.Helper()
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	for {
		select {
		case raw := <-c.out:
			var event proto.MessageSendState
			if json.Unmarshal(raw, &event) == nil && event.Type == "message_send_state" {
				return event
			}
		case <-timer.C:
			t.Fatal("no message receipt within one second")
		}
	}
}

// Use the real bus framing, but a blocked parameter provider, not a paid model.
func TestSendReceiptPrecedesManagerPreparation(t *testing.T) {
	h := newTestHub(t)
	h.store = newSentStore()
	var typed, prepared atomic.Int32
	pipeNode(t, h, "box", func(context.Context) error { typed.Add(1); return nil })
	m := h.byID["box"]
	editSessions(m, func([]proto.Session) []proto.Session {
		return []proto.Session{{SessionID: "s1", Assistant: true, Status: proto.StatusWorking}}
	})
	h.bus.idents = &memIdents{}
	mux := http.NewServeMux()
	h.busRoutes(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	token, err := h.createBusToken(context.Background(), assistantwire.AssistantID, "Test manager")
	if err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	t.Cleanup(func() { once.Do(func() { close(release) }) })
	connectGuardManagerWith(t, srv.URL, assistantwire.AssistantID, token, func(method string, raw json.RawMessage) (any, error) {
		if method != assistantwire.MethodSendParams {
			return map[string]any{}, nil
		}
		prepared.Add(1)
		close(entered)
		<-release
		var params assistantwire.SendParams
		_ = json.Unmarshal(raw, &params)
		return map[string]any{"params": params.Params}, nil
	})
	c, other := sendSubscriber(h, "box", "s1"), sendSubscriber(h, "box", "other")
	request := func() *http.Request {
		r := httptest.NewRequest(http.MethodPost, sendPath, strings.NewReader(`{"text":"hello","client_msg_id":"receipt-1"}`))
		r.SetPathValue("sid", "s1")
		return r
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := request().WithContext(ctx)
	done := make(chan int, 2)
	start := time.Now()
	go func() { w := httptest.NewRecorder(); h.sendMessage(w, r, m); done <- w.Code }()
	event := nextSendState(t, c)
	if event.State != proto.MessageSendProcessing || event.ClientMsgID != "receipt-1" || event.MachineID != "box" || event.SessionID != "s1" || event.At.IsZero() {
		t.Fatalf("receipt: %+v", event)
	}
	t.Logf("isolated hub + real bus / stub manager: processing=%s", time.Since(start))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("manager preparation did not start")
	}
	cancel() // receipt must not depend on the HTTP caller staying connected
	go func() { w := httptest.NewRecorder(); h.sendMessage(w, request(), m); done <- w.Code }()
	if row, _ := m.session("s1"); row.Status != proto.StatusWorking {
		t.Fatalf("receipt overwrote the executing turn: %+v", row)
	}
	if typed.Load() != 0 {
		t.Fatal("delivered before preparation completed")
	}
	select {
	case <-done:
		t.Fatal("HTTP final arrived before delivery")
	default:
	}
	once.Do(func() { close(release) })
	for range 2 {
		select {
		case status := <-done:
			if status != http.StatusOK {
				t.Fatalf("HTTP final=%d", status)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("send did not finish")
		}
	}
	if typed.Load() != 1 || prepared.Load() != 1 {
		t.Fatalf("typed=%d prepared=%d", typed.Load(), prepared.Load())
	}
	if event = nextSendState(t, c); event.State != proto.MessageSendDelivered {
		t.Fatalf("final=%+v", event)
	}
	if broadcasts(other, "message_send_state") != 0 {
		t.Fatal("receipt leaked to another session's subscriber")
	}
	// A duplicate can replay only the final state, never Processing again.
	for {
		select {
		case raw := <-c.out:
			var event proto.MessageSendState
			_ = json.Unmarshal(raw, &event)
			if event.Type == "message_send_state" && event.State != proto.MessageSendDelivered {
				t.Fatalf("duplicate regressed state: %+v", event)
			}
		default:
			return
		}
	}
}

func TestSendReceiptTerminalOutcomesAndRetry(t *testing.T) {
	for _, tc := range []struct {
		name, state string
		err         error
	}{
		{"delivered", proto.MessageSendDelivered, nil},
		{"refused", proto.MessageSendFailed, &rpc.Error{Code: "input_draft", Message: "draft", Data: json.RawMessage(`{"delivered":"none"}`)}},
		{"unknown", proto.MessageSendUnknown, &rpc.Error{Message: "link lost", Data: json.RawMessage(`{"delivered":"unknown"}`)}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newTestHub(t)
			h.store = newSentStore()
			var typed atomic.Int32
			pipeNode(t, h, "box", func(context.Context) error { typed.Add(1); return tc.err })
			c := sendSubscriber(h, "box", "s1")
			body := `{"text":"hello","client_msg_id":"receipt-2"}`
			call(t, h.routes(), http.MethodPost, sendPath, h.cfg.Token, body)
			if event := nextSendState(t, c); event.State != proto.MessageSendProcessing {
				t.Fatalf("initial=%+v", event)
			}
			if event := nextSendState(t, c); event.State != tc.state {
				t.Fatalf("terminal=%+v", event)
			}
			call(t, h.routes(), http.MethodPost, sendPath, h.cfg.Token, body)
			if tc.state != proto.MessageSendFailed && broadcasts(c, "message_send_state") != 0 {
				t.Fatal("cached HTTP retry rebroadcast an older send state")
			}
			want := int32(1)
			if tc.state == proto.MessageSendFailed {
				want = 2
			}
			if typed.Load() != want {
				t.Fatalf("retry typed=%d want=%d", typed.Load(), want)
			}
		})
	}
}

func TestSendReceiptValidatesAnswerOnlyBeforeCachedRetry(t *testing.T) {
	h := newTestHub(t)
	pipeNode(t, h, "box", func(context.Context) error { return nil })
	body := `{"text":"hello","client_msg_id":"receipt-admission"}`
	call(t, h.routes(), http.MethodPost, sendPath, h.cfg.Token, body)
	c := sendSubscriber(h, "box", "missing")
	r := httptest.NewRequest(http.MethodPost, sendPath, strings.NewReader(body))
	r.SetPathValue("sid", "missing")
	r = r.WithContext(context.WithValue(r.Context(), principalKey{}, principal{Role: roleAnswer}))
	w := httptest.NewRecorder()
	h.sendMessage(w, r, h.byID["box"])
	if w.Code != http.StatusNotFound || broadcasts(c, "message_send_state") != 0 {
		t.Fatalf("cached retry bypassed target admission: status=%d", w.Code)
	}
}

func TestSendReceiptPreparationFailureCanRetry(t *testing.T) {
	h := newTestHub(t)
	pipeNode(t, h, "box", func(context.Context) error { return nil })
	editSessions(h.byID["box"], func([]proto.Session) []proto.Session {
		return []proto.Session{{SessionID: "s1", Assistant: true}}
	}) // no manager: parameter generation fails before node delivery
	c := sendSubscriber(h, "box", "s1")
	for range 2 {
		status, body := call(t, h.routes(), http.MethodPost, sendPath, h.cfg.Token, `{"text":"hello","client_msg_id":"receipt-3"}`)
		if status != http.StatusServiceUnavailable {
			t.Fatalf("offline manager status=%d", status)
		}
		if body["code"] != assistantwire.CodeAssistantOffline || body["delivered"] != "none" || body["retryable"] != true {
			t.Fatalf("offline manager body=%+v", body)
		}
		if event := nextSendState(t, c); event.State != proto.MessageSendProcessing {
			t.Fatalf("initial=%+v", event)
		}
		if event := nextSendState(t, c); event.State != proto.MessageSendFailed {
			t.Fatalf("failure=%+v", event)
		}
	}
}

type sendClientOpStore struct {
	*sentStore
	ops *clientOpsStore
}

func (s *sendClientOpStore) BeginClientOp(ctx context.Context, op db.ClientOp) (*db.ClientOp, bool, error) {
	return s.ops.BeginClientOp(ctx, op)
}
func (s *sendClientOpStore) ClientOp(ctx context.Context, id string) (*db.ClientOp, error) {
	return s.ops.ClientOp(ctx, id)
}
func (s *sendClientOpStore) FinishClientOp(ctx context.Context, id, state string, result json.RawMessage) error {
	return s.ops.FinishClientOp(ctx, id, state, result)
}
func (s *sendClientOpStore) ForgetClientOp(_ context.Context, id string) error {
	s.ops.mu.Lock()
	defer s.ops.mu.Unlock()
	delete(s.ops.ops, id)
	return nil
}

func TestSendMessageAssistantOfflineReleasesClientOpAndRetriesSameID(t *testing.T) {
	const opID = "11111111-1111-4111-8111-111111111111"
	h := newTestHub(t)
	store := &sendClientOpStore{sentStore: newSentStore(), ops: &clientOpsStore{ops: map[string]db.ClientOp{}}}
	h.store = store
	var typed atomic.Int32
	pipeNode(t, h, "box", func(context.Context) error { typed.Add(1); return nil })
	editSessions(h.byID["box"], func([]proto.Session) []proto.Session {
		return []proto.Session{{SessionID: "s1", Assistant: true}}
	})

	routes := h.routes()
	post := func() (int, map[string]any) {
		r := httptest.NewRequest(http.MethodPost, sendPath, strings.NewReader(`{"text":"hello","client_msg_id":"`+opID+`"}`))
		r.RemoteAddr = "127.0.0.1:1"
		r.Header.Set("Authorization", "Bearer "+h.cfg.Token)
		r.Header.Set("X-Persona-Op-Id", opID)
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, r)
		var out map[string]any
		_ = json.Unmarshal(w.Body.Bytes(), &out)
		return w.Code, out
	}

	status, body := post()
	if status != http.StatusServiceUnavailable {
		t.Fatalf("first status = %d, body = %+v", status, body)
	}
	if body["code"] != assistantwire.CodeAssistantOffline || body["delivered"] != "none" || body["retryable"] != true {
		t.Fatalf("first body = %+v, want assistant_offline with delivered=none and retryable=true", body)
	}
	if typed.Load() != 0 {
		t.Fatalf("typed = %d while assistant offline, want 0", typed.Load())
	}
	if op, err := store.ClientOp(context.Background(), opID); err != nil || op != nil {
		t.Fatalf("client op was kept after assistant_offline: op=%+v err=%v", op, err)
	}

	// Bring the assistant manager online and retry with the exact same X-Persona-Op-Id.
	h.bus.idents = &memIdents{}
	busMux := http.NewServeMux()
	h.busRoutes(busMux)
	srv := httptest.NewServer(busMux)
	t.Cleanup(srv.Close)
	token, err := h.createBusToken(context.Background(), assistantwire.AssistantID, "Test manager")
	if err != nil {
		t.Fatal(err)
	}
	connectGuardManagerWith(t, srv.URL, assistantwire.AssistantID, token, func(method string, raw json.RawMessage) (any, error) {
		if method != assistantwire.MethodSendParams {
			return map[string]any{}, nil
		}
		var params assistantwire.SendParams
		_ = json.Unmarshal(raw, &params)
		return map[string]any{"params": params.Params}, nil
	})

	status, body = post()
	if status != http.StatusOK || typed.Load() != 1 {
		t.Fatalf("retry after manager online: status=%d body=%+v typed=%d", status, body, typed.Load())
	}
	// Repeating the same op id after success replays the done receipt without typing a second time.
	status, body = post()
	if status != http.StatusOK || typed.Load() != 1 {
		t.Fatalf("idempotent replay: status=%d body=%+v typed=%d", status, body, typed.Load())
	}
}
