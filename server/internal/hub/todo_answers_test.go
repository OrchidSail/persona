package hub

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/i18n"
)

type countingTodos struct {
	mu       sync.Mutex
	body     []byte
	revision string
	status   int
	gate     chan struct{}
	listed   atomic.Int32
	revReads atomic.Int32
}

func (c *countingTodos) serve(ctx context.Context, r assistantwire.HTTPRequest) (any, error) {
	if r.Method == http.MethodGet && r.Path == "/api/v1/todos/revision" {
		c.revReads.Add(1)
		c.mu.Lock()
		rev := c.revision
		c.mu.Unlock()
		hd := http.Header{"Content-Type": {"application/json; charset=utf-8"}}
		if rev != "" {
			hd.Set(assistantwire.TodoRevisionHeader, rev)
		}
		return assistantwire.HTTPResponse{Status: http.StatusOK, Header: hd, Body: json.RawMessage(`{"revision":""}`)}, nil
	}
	if r.Method != http.MethodGet || r.Path != "/api/v1/todos" {
		return assistantwire.HTTPResponse{
			Status: 200,
			Header: http.Header{"Content-Type": {"application/json"}},
			Body:   json.RawMessage(`{"ok":true}`),
		}, nil
	}
	c.listed.Add(1)
	c.mu.Lock()
	body, rev, status, gate := c.body, c.revision, cmp0(c.status, 200), c.gate
	c.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	hd := http.Header{"Content-Type": {"application/json; charset=utf-8"}}
	if rev != "" {
		hd.Set(assistantwire.TodoRevisionHeader, rev)
	}
	return assistantwire.HTTPResponse{Status: status, Header: hd, Body: body}, nil
}

func (c *countingTodos) set(f func(c *countingTodos)) {
	c.mu.Lock()
	f(c)
	c.mu.Unlock()
}

func newCountingTodos(t *testing.T, h *Hub, body string, rev string) *countingTodos {
	t.Helper()
	m, _ := connectInboxManager(t, h)
	c := &countingTodos{body: []byte(body), revision: rev}
	m.mu.Lock()
	m.http = c.serve
	m.mu.Unlock()
	return c
}

func (c *todoAnswers) waiting() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, call := range c.calls {
		n += call.waiters
	}
	return n
}

func (c *todoAnswers) expire() {
	c.mu.Lock()
	for _, a := range c.kept {
		a.at = a.at.Add(-max(todoAnswerTTL, todoAnswerValidatedTTL))
	}
	c.mu.Unlock()
}

func TestMergedTodosCoalescesConcurrentReadsAndServes304AndInvalidates(t *testing.T) {
	h := newTestHub(t)
	bodyV1 := fmt.Sprintf(`{"todos":[{"id":"todo_1","title":"%s"}],"counts":{"open":1}}`, strings.Repeat("a", 2048))
	m := newCountingTodos(t, h, bodyV1, `W/"tr1-v1"`)
	gate := make(chan struct{})
	m.set(func(c *countingTodos) { c.gate = gate })
	routes := h.routes()

	const k = 6
	recs := make([]*httptest.ResponseRecorder, k)
	var wg sync.WaitGroup
	for i := range k {
		wg.Go(func() {
			recs[i] = getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, i%2 == 0)
		})
	}
	eventually(t, 5*time.Second, "concurrent todo readers wait on singleflight", func() bool {
		return m.listed.Load() == 1 && h.todoAnswers.waiting() == k-1
	})
	close(gate)
	wg.Wait()

	if n := m.listed.Load(); n != 1 {
		t.Fatalf("%d concurrent todo readers triggered %d manager calls, want 1", k, n)
	}
	want := decodedBody(t, recs[0])
	etag := recs[0].Header().Get("ETag")
	rev := recs[0].Header().Get(assistantwire.TodoRevisionHeader)
	if !strings.HasPrefix(etag, `W/"t1-`) || !strings.HasPrefix(rev, `W/"tr1-hub-`) {
		t.Fatalf("missing validator headers: etag=%q rev=%q", etag, rev)
	}
	for i, rec := range recs {
		if rec.Code != 200 || !bytes.Equal(decodedBody(t, rec), want) || rec.Header().Get("ETag") != etag {
			t.Fatalf("reader %d: status=%d etag=%q", i, rec.Code, rec.Header().Get("ETag"))
		}
	}

	// Subsequent conditional request revalidates via /todos/revision and answers 304 with zero full list rebuilds.
	notMod := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, true, "If-None-Match", etag)
	if notMod.Code != http.StatusNotModified || notMod.Body.Len() != 0 || notMod.Header().Get("ETag") != etag || m.listed.Load() != 1 || m.revReads.Load() != 1 {
		t.Fatalf("cached 304: status=%d len=%d etag=%q listed=%d revReads=%d", notMod.Code, notMod.Body.Len(), notMod.Header().Get("ETag"), m.listed.Load(), m.revReads.Load())
	}

	// Plain GET /api/v1/todos uses a distinct cache key from ?compact=1.
	plain := getInbox(t.Context(), h, routes, "/api/v1/todos", i18n.En, false)
	if plain.Code != 200 || m.listed.Load() != 2 {
		t.Fatalf("plain query did not bypass compact key: status=%d listed=%d", plain.Code, m.listed.Load())
	}

	// A manager revision change BEFORE any {"type":"todos"} broadcast (during todosSettle) invalidates immediately.
	m.set(func(c *countingTodos) {
		c.body = []byte(`{"todos":[{"id":"todo_1","title":"before-broadcast"}],"counts":{"open":1}}`)
		c.revision = `W/"tr1-v1b"`
		c.gate = nil
	})
	beforePush := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, false, "If-None-Match", etag)
	if beforePush.Code != 200 || !strings.Contains(beforePush.Body.String(), `"before-broadcast"`) || m.listed.Load() != 3 {
		t.Fatalf("after revision bump without broadcast: status=%d listed=%d body=%s", beforePush.Code, m.listed.Load(), beforePush.Body.String())
	}

	// A {"type":"todos"} broadcast invalidates the kept answer immediately.
	m.set(func(c *countingTodos) {
		c.body = []byte(`{"todos":[{"id":"todo_1","title":"updated"}],"counts":{"open":1}}`)
		c.revision = `W/"tr1-v2"`
		c.gate = nil
	})
	if err := h.relayBroadcastFrom(assistantwire.AssistantID, json.RawMessage(`{"type":"todos"}`)); err != nil {
		t.Fatal(err)
	}
	afterPush := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, false, "If-None-Match", etag)
	if afterPush.Code != 200 || !strings.Contains(afterPush.Body.String(), `"updated"`) || m.listed.Load() != 4 {
		t.Fatalf("after todos broadcast: status=%d listed=%d body=%s", afterPush.Code, m.listed.Load(), afterPush.Body.String())
	}

	// A non-GET write to the manager invalidates the kept answer too.
	postReq := localRequest("POST", "/api/v1/todos")
	postReq.Header.Set("Authorization", "Bearer "+h.cfg.Token)
	postRec := httptest.NewRecorder()
	routes.ServeHTTP(postRec, postReq)
	if postRec.Code != 200 {
		t.Fatalf("POST /api/v1/todos: %d %s", postRec.Code, postRec.Body.String())
	}
	afterWrite := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, false)
	if afterWrite.Code != 200 || m.listed.Load() != 5 {
		t.Fatalf("after write: status=%d listed=%d", afterWrite.Code, m.listed.Load())
	}

	// ownersChanged invalidates all kept answers.
	h.ownersChanged()
	afterOwners := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, false)
	if afterOwners.Code != 200 || m.listed.Load() != 6 {
		t.Fatalf("after ownersChanged: status=%d listed=%d", afterOwners.Code, m.listed.Load())
	}

	// TTL expiry also forces a fresh fetch.
	h.todoAnswers.expire()
	afterExpire := getInbox(t.Context(), h, routes, "/api/v1/todos?compact=1", i18n.En, false)
	if afterExpire.Code != 200 || m.listed.Load() != 7 {
		t.Fatalf("after expire: status=%d listed=%d", afterExpire.Code, m.listed.Load())
	}
}
