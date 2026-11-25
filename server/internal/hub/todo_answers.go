package hub

import (
	"bytes"
	"cmp"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/i18n"
)

// The merged to-dos list (GET /api/v1/todos naming no manager, unpaginated) is requested by every connected app
// on reconnect and after each {"type":"todos"} push. Without hub-level coalescing and reuse, a push to k apps
// makes each manager format, encode and send its full list k times and the hub scan, merge and gzip it k times
// for identical bytes. Mirroring inboxAnswers (inbox_answers.go), a 2xx answer from every manager is kept briefly,
// gzipped once on demand, validated with ETag/If-None-Match (304), and invalidated immediately before any
// {"type":"todos"} broadcast, around any non-GET/HEAD request to a manager, and on ownersChanged. When managers
// expose a merged X-Persona-Todos-Revision token, cached hits are revalidated against the cheap /todos/revision
// probe so mutations inside the manager's debounced {"type":"todos"} window never serve stale rows.
const (
	todoAnswerTTL          = 5 * time.Second
	todoAnswerValidatedTTL = 30 * time.Second
)

const (
	todoAnswersMax     = 8        // answers kept at most (one per request shape: role, language, query)
	todoAnswersMaxSize = 48 << 20 // their bytes at most (todoAnswer.counted)
	todoAnswerMaxBody  = 16 << 20 // a larger answer serves its waiters and is not kept
)

type todoAnswerKey struct {
	path, query, role string
	lang              i18n.Lang
	managers          string // the managers asked, in order, a newline after each
}

type todoAnswers struct {
	off bool // tests: every request fetches its own

	mu     sync.Mutex
	global uint64
	gens   map[string]uint64 // manager id -> its todos generation
	kept   map[todoAnswerKey]*todoAnswer
	calls  map[todoAnswerKey]*todoCall
	size   int // of kept
}

type todoCall struct {
	stamp uint64
	at    time.Time
	done  chan struct{}
	ans   *todoAnswer // set before done closes

	waiters int // requests that joined this in-flight fetch (under todoAnswers.mu)
}

type todoAnswer struct {
	status   int
	header   http.Header
	body     []byte
	tag      string // W/"t1-..." ETag for 200 OK answers
	revision string // W/"tr1-hub-..." when every manager supplied X-Persona-Todos-Revision
	shared   bool   // every manager asked answered 2xx and so did the merge

	stamp uint64
	at    time.Time

	gzOnce sync.Once
	gz     []byte

	kept    bool
	counted int
}

func (c *todoAnswers) changed(id string) {
	c.mu.Lock()
	if c.gens == nil {
		c.gens = map[string]uint64{}
	}
	c.gens[id]++
	c.mu.Unlock()
}

func (c *todoAnswers) changedAll() {
	c.mu.Lock()
	c.global++
	c.mu.Unlock()
}

func (c *todoAnswers) stampOf(ids []string) uint64 {
	s := c.global
	for _, id := range ids {
		s += c.gens[id]
	}
	return s
}

func (c *todoAnswers) join(k todoAnswerKey, ids []string, now time.Time) (kept *todoAnswer, call *todoCall, leader bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	stamp := c.stampOf(ids)
	if a := c.kept[k]; a != nil {
		ttl := todoAnswerTTL
		if a.revision != "" {
			ttl = todoAnswerValidatedTTL
		}
		if a.stamp == stamp && now.Sub(a.at) < ttl {
			return a, nil, false
		}
		c.drop(k)
	}
	if call := c.calls[k]; call != nil && call.stamp == stamp {
		call.waiters++
		return nil, call, false
	}
	if c.calls == nil {
		c.calls = map[todoAnswerKey]*todoCall{}
	}
	call = &todoCall{stamp: stamp, at: now, done: make(chan struct{})}
	c.calls[k] = call
	return nil, call, true
}

func (c *todoAnswers) invalidate(k todoAnswerKey, a *todoAnswer) {
	c.mu.Lock()
	if c.kept[k] == a {
		c.drop(k)
	}
	c.mu.Unlock()
}

func (c *todoAnswers) finish(k todoAnswerKey, ids []string, call *todoCall, a *todoAnswer) {
	c.mu.Lock()
	if c.calls[k] == call {
		delete(c.calls, k)
	}
	if a.shared && len(a.body) <= todoAnswerMaxBody && c.stampOf(ids) == call.stamp {
		a.stamp, a.at = call.stamp, call.at
		c.keep(k, a)
	}
	c.mu.Unlock()
	call.ans = a
	close(call.done)
}

func (c *todoAnswers) keep(k todoAnswerKey, a *todoAnswer) {
	c.drop(k)
	if c.kept == nil {
		c.kept = map[todoAnswerKey]*todoAnswer{}
	}
	for len(c.kept) > 0 && (len(c.kept) >= todoAnswersMax || c.size+len(a.body) > todoAnswersMaxSize) {
		c.dropOldest(nil)
	}
	c.kept[k] = a
	a.kept, a.counted = true, len(a.body)
	c.size += a.counted
}

func (c *todoAnswers) dropOldest(except *todoAnswer) {
	var oldest todoAnswerKey
	var at time.Time
	found := false
	for key, b := range c.kept {
		if b != except && (!found || b.at.Before(at)) {
			oldest, at, found = key, b.at, true
		}
	}
	if found {
		c.drop(oldest)
	}
}

func (c *todoAnswers) drop(k todoAnswerKey) {
	if a := c.kept[k]; a != nil {
		c.size -= a.counted
		a.kept, a.counted = false, 0
		delete(c.kept, k)
	}
}

func (c *todoAnswers) gzipped(a *todoAnswer) []byte {
	a.gzOnce.Do(func() {
		var b bytes.Buffer
		gz := gzipWriters.Get().(*gzip.Writer)
		gz.Reset(&b)
		_, _ = gz.Write(a.body)
		_ = gz.Close()
		gz.Reset(io.Discard)
		gzipWriters.Put(gz)
		c.mu.Lock()
		a.gz = b.Bytes()
		if a.kept {
			a.counted += len(a.gz)
			c.size += len(a.gz)
			for c.size > todoAnswersMaxSize && len(c.kept) > 1 {
				c.dropOldest(a)
			}
		}
		c.mu.Unlock()
	})
	return a.gz
}

// mergedTodos answers an unpaginated merged GET /api/v1/todos with singleflight coalescing, short TTL reuse,
// gzip-once compression, and ETag/If-None-Match 304 validation. Returns false when the request should follow
// plain mergeList (named manager, extension caller, non-GET, body present, or cache disabled).
func (h *Hub) mergedTodos(w http.ResponseWriter, r *http.Request, key string, ask func(w http.ResponseWriter, r *http.Request, id string)) bool {
	c := &h.todoAnswers
	if todoAnswerTTL <= 0 || c.off || r.Method != http.MethodGet || r.ContentLength != 0 {
		return false
	}
	query, err := url.ParseQuery(r.URL.RawQuery)
	named, nerr := impliedManager(r)
	if err != nil || nerr != nil || named != "" || principalOf(r.Context()).Role == roleExtension {
		return false
	}
	ids := h.listManagers(r.Context())
	k := todoAnswerKey{
		path: r.URL.Path, query: query.Encode(), role: principalOf(r.Context()).Role, lang: h.requestLang(r),
		managers: strings.Join(ids, "\n") + "\n",
	}
	revalidated := false
	for {
		kept, call, leader := c.join(k, ids, time.Now())
		switch {
		case kept != nil:
			if kept.revision != "" && !h.todoRevisionMatches(r, ids, ask, kept.revision) {
				c.invalidate(k, kept)
				if !revalidated {
					revalidated = true
					continue
				}
				c.write(w, r, h.mergedTodoAnswer(r, key, ids, ask))
				return true
			}
			c.write(w, r, kept)
			return true
		case leader:
			c.write(w, r, h.leadTodos(r, key, k, ids, call, ask))
			return true
		}
		select {
		case <-call.done:
			if call.ans.shared {
				c.write(w, r, call.ans)
				return true
			}
		case <-r.Context().Done():
		}
		c.write(w, r, h.mergedTodoAnswer(r, key, ids, ask))
		return true
	}
}

func (h *Hub) todoRevisionMatches(r *http.Request, ids []string, ask func(w http.ResponseWriter, r *http.Request, id string), want string) bool {
	if want == "" || len(ids) == 0 {
		return false
	}
	answers := make([]listAnswer, 0, len(ids))
	for _, id := range ids {
		bw := newBufferedWriter()
		asked := withBody(r, nil)
		u := *asked.URL
		u.Path = "/api/v1/todos/revision"
		u.RawQuery = ""
		asked.URL = &u
		asked.Header.Del("If-None-Match")
		ask(bw, asked, id)
		answers = append(answers, listAnswer{id: id, status: cmp.Or(bw.status, http.StatusOK), header: bw.header, body: bw.body.Bytes()})
	}
	tag, complete := todoRevisionOfAnswers(answers)
	return complete && tag == want
}

func (h *Hub) leadTodos(r *http.Request, key string, k todoAnswerKey, ids []string, call *todoCall, ask func(w http.ResponseWriter, r *http.Request, id string)) *todoAnswer {
	c := &h.todoAnswers
	finished := false
	defer func() {
		if !finished {
			c.finish(k, ids, call, &todoAnswer{})
		}
	}()
	a := h.mergedTodoAnswer(r, key, ids, ask)
	c.finish(k, ids, call, a)
	finished = true
	return a
}

func (h *Hub) mergedTodoAnswer(r *http.Request, key string, ids []string, ask func(w http.ResponseWriter, r *http.Request, id string)) *todoAnswer {
	out := newBufferedWriter()
	if len(ids) == 0 {
		writeErrCode(out, http.StatusServiceUnavailable, assistantwire.CodeAssistantOffline, errAssistantOffline)
		return &todoAnswer{status: out.status, header: out.header, body: out.body.Bytes()}
	}
	shared := true
	answers := make([]listAnswer, 0, len(ids))
	for _, id := range ids {
		bw := newBufferedWriter()
		asked := withBody(r, nil)
		asked.Header.Del("If-None-Match")
		ask(bw, asked, id)
		ans := listAnswer{id: id, status: cmp.Or(bw.status, http.StatusOK), header: bw.header, body: bw.body.Bytes(), localize: bw.localize}
		if ans.ok() {
			h.noteTodoOwners(&ans)
		}
		shared = shared && ans.ok()
		answers = append(answers, ans)
	}
	writeMergedSet(out, key, answers, todoListSums(answers))
	status := cmp.Or(out.status, http.StatusOK)
	body := out.body.Bytes()
	tag := ""
	revision := ""
	if status == http.StatusOK {
		out.header.Del(assistantwire.TodoRevisionHeader)
		if rev, complete := todoRevisionOfAnswers(answers); complete {
			revision = rev
			out.header.Set(assistantwire.TodoRevisionHeader, rev)
		}
		sum := sha256.Sum256(body)
		tag = `W/"t1-` + hex.EncodeToString(sum[:16]) + `"`
	}
	return &todoAnswer{
		status:   status,
		header:   out.header,
		body:     body,
		tag:      tag,
		revision: revision,
		shared:   shared && status >= 200 && status < 300,
	}
}

func (c *todoAnswers) write(w http.ResponseWriter, r *http.Request, a *todoAnswer) {
	hd := w.Header()
	for k, v := range a.header {
		hd[k] = slices.Clone(v)
	}
	gz := a.shared && gzipWanted(r) && gzipsAs(a.status, hd, a.body)
	if a.status == http.StatusOK && a.tag != "" {
		hd.Set("ETag", a.tag)
		if inm := r.Header.Values("If-None-Match"); len(inm) > 0 && tagMatches(inm, a.tag) {
			hd.Del("Content-Length")
			if gz {
				hd.Add("Vary", "Accept-Encoding")
			}
			w.WriteHeader(http.StatusNotModified)
			return
		}
	}
	body := a.body
	if gz {
		body = c.gzipped(a)
		hd.Del("Content-Length")
		hd.Set("Content-Encoding", "gzip")
		hd.Add("Vary", "Accept-Encoding")
	}
	w.WriteHeader(a.status)
	_, _ = w.Write(body)
}
