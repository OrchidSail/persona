package assistant

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/db"
	"github.com/nwverse/persona/server/internal/proto"
)

// trustMemStore keeps extension trusts and grants, information categories and the track registry in memory.
type trustMemStore struct {
	*infoCategoryMemStore
	tmu    sync.Mutex
	trusts map[string]db.ExtensionTrust
	next   int
}

func (st *trustMemStore) ExtensionTrustOf(_ context.Context, extension string) (string, error) {
	st.tmu.Lock()
	defer st.tmu.Unlock()
	return st.trusts[extension].Trust, nil
}

func (st *trustMemStore) ExtensionTrusts(context.Context) (map[string]db.ExtensionTrust, error) {
	st.tmu.Lock()
	defer st.tmu.Unlock()
	out := map[string]db.ExtensionTrust{}
	for k, v := range st.trusts {
		v.Categories = slices.Clone(v.Categories)
		out[k] = v
	}
	return out, nil
}

func (st *trustMemStore) SetExtensionTrust(ctx context.Context, ext, trust string, cats []string, by string) error {
	_, err := st.ReplaceExtensionTrust(ctx, ext, trust, cats, by, nil)
	return err
}

func (st *trustMemStore) ReplaceExtensionTrust(_ context.Context, ext, trust string, cats []string, _ string, expected *string) (db.ExtensionTrust, error) {
	st.tmu.Lock()
	defer st.tmu.Unlock()
	current, found := st.trusts[ext]
	if !found {
		current = db.ExtensionTrust{Extension: ext, Revision: db.DefaultExtensionTrustRevision}
	}
	if expected != nil && *expected != current.Revision {
		return current, db.ErrExtensionTrustMoved
	}
	if st.trusts == nil {
		st.trusts = map[string]db.ExtensionTrust{}
	}
	st.next++
	current = db.ExtensionTrust{Extension: ext, Trust: trust, Categories: slices.Clone(cats), Revision: fmt.Sprint(st.next)}
	st.trusts[ext] = current
	return current, nil
}

func (st *trustMemStore) ForgetExtensionTrust(_ context.Context, ext string) error {
	st.tmu.Lock()
	defer st.tmu.Unlock()
	delete(st.trusts, ext)
	return nil
}

// trustService is conversationsService's assistant (its own conversations, a guest's, the health space's) on a store
// that keeps extension trusts and information categories.
func trustService(t *testing.T) (*Service, *trustMemStore) {
	t.Helper()
	f := healthHub()
	f.sessions["server"] = []proto.Session{
		{MachineID: "server", SessionID: "main-0000-0000", Cwd: "/home/me/.persona/assistant", Assistant: true, Title: "Persona", UpdatedAt: convDay.Add(5 * time.Hour)},
		{MachineID: "server", SessionID: "slee-0000-0000", Cwd: "/home/me/.persona/health", Assistant: true, Space: SpaceHealth, UpdatedAt: convDay.Add(4 * time.Hour)},
	}
	st := &trustMemStore{infoCategoryMemStore: &infoCategoryMemStore{trackRegistryStore: &trackRegistryStore{memStore: &memStore{}}}}
	s := newServiceOn(t, f, st)
	setSettings(t, s, func(v *Settings) { v.MachineID, v.Main = "server", "main-0000-0000" })
	return s, st
}

// extensionListedIDs are the conversation ids assistant.conversations shows, asked plainly (ext "") or for extension ext.
func extensionListedIDs(t *testing.T, s *Service, ext string) string {
	t.Helper()
	method, params := assistantwire.MethodAssistantConversations, `{"since":"2026-01-02T00:00:00Z"}`
	if ext != "" {
		b, _ := json.Marshal(assistantwire.AssistantExtensionRead{Extension: ext, Method: method, Params: json.RawMessage(params)})
		method, params = assistantwire.MethodAssistantExtensionRead, string(b)
	}
	var res assistantwire.AssistantConversationsResult
	if err := askConversations(t, s, method, params, &res); err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, c := range res.Items {
		ids = append(ids, c.ID)
	}
	return strings.Join(ids, ",")
}

// What an extension reads of the health space (#13246, scene 6): nothing unless the user marked it their own (trust
// owner, such as their memory extension) or granted it the health category; a plain read, which names no extension,
// is a third party's with no grant.
func TestExtensionTrustDecidesReads(t *testing.T) {
	ctx := context.Background()
	s, st := trustService(t)
	if got := extensionListedIDs(t, s, ""); got != "main-0000-0000" {
		t.Fatalf("plain read: %s", got)
	}
	if got := extensionListedIDs(t, s, "qa-memory"); got != "main-0000-0000" {
		t.Fatalf("an extension the user never marked: %s", got)
	}
	_ = st.SetExtensionTrust(ctx, "qa-memory", assistantwire.ExtensionTrustOwner, nil, "user")
	if got := extensionListedIDs(t, s, "qa-memory"); got != "main-0000-0000,slee-0000-0000" {
		t.Fatalf("the user's own extension: %s", got)
	}
	if got := extensionListedIDs(t, s, "qa-other"); got != "main-0000-0000" {
		t.Fatalf("another extension, while one is the user's own: %s", got)
	}
	_ = st.SetExtensionTrust(ctx, "qa-other", assistantwire.ExtensionTrustThirdParty, []string{categoryFinance}, "user")
	if got := extensionListedIDs(t, s, "qa-other"); got != "main-0000-0000" {
		t.Fatalf("a third party granted finance only: %s", got)
	}
	_ = st.SetExtensionTrust(ctx, "qa-other", assistantwire.ExtensionTrustThirdParty, []string{categoryHealth}, "user")
	if got := extensionListedIDs(t, s, "qa-other"); got != "main-0000-0000,slee-0000-0000" {
		t.Fatalf("a third party granted health: %s", got)
	}

	// A main conversation tagged with a sensitive category is hidden from a third party not granted it.
	if err := s.tagConversation(ctx, "main-0000-0000", []string{categoryFinance}, infoTaggedByModel, ""); err != nil {
		t.Fatal(err)
	}
	if got := extensionListedIDs(t, s, "qa-third"); got != "" {
		t.Fatalf("a third party, the main conversation tagged finance: %q", got)
	}
	if got := extensionListedIDs(t, s, "qa-memory"); got != "main-0000-0000,slee-0000-0000" {
		t.Fatalf("the user's own extension, the main conversation tagged finance: %s", got)
	}

	// Other reads follow the same grant: the health space's memory is the owner's to read, not a third party's.
	if !s.extensionHides(s.withExtensionReader(ctx, "qa-third"), SpaceHealth) || s.extensionHides(s.withExtensionReader(ctx, "qa-memory"), SpaceHealth) {
		t.Fatal("extensionHides does not follow the grant")
	}
	if !s.extensionHides(ctx, SpaceHealth) || s.extensionHides(ctx, "") {
		t.Fatal("a read with no extension named is not a third party's with no grant")
	}

	// A removed extension's trust goes with it: installed again, it starts as a third party's.
	var none json.RawMessage
	if err := askConversations(t, s, assistantwire.MethodExtensionTrustForget, `{"extension":"qa-memory"}`, &none); err != nil || string(none) != `{}` {
		t.Fatalf("forget trust reply: %s, %v", none, err)
	}
	if _, kept := st.trusts["qa-memory"]; kept || !s.extensionHides(s.withExtensionReader(ctx, "qa-memory"), SpaceHealth) {
		t.Fatal("a removed extension is still the user's own")
	}
}

// Only the user changes who reads what or what a conversation carries (#13246, acceptance 14): an extension's token
// or a scoped device gets 403; the user's own changes are kept, and a track's own categories cannot be taken away.
func TestInfoAccessRoutesAreTheUsers(t *testing.T) {
	s, st := trustService(t)
	for _, role := range []string{roleExtension, "agent", "scoped"} {
		if w := serveAs(s, role, http.MethodPut, "/api/v1/assistant/extension-trust/qa-memory", `{"trust":"owner","categories":[]}`); w.Code != http.StatusForbidden {
			t.Errorf("%s set trust: %d", role, w.Code)
		}
		if w := serveAs(s, role, http.MethodPut, "/api/v1/assistant/conversations/main-0000-0000/categories", `{"remove":["finance"]}`); w.Code != http.StatusForbidden {
			t.Errorf("%s change categories: %d", role, w.Code)
		}
		if w := serveAs(s, role, http.MethodGet, "/api/v1/assistant/extension-trust", ""); w.Code != http.StatusForbidden {
			t.Errorf("%s list trusts: %d", role, w.Code)
		}
	}
	if len(st.trusts) != 0 {
		t.Fatalf("kept %v", st.trusts)
	}
	if w := serve(s, http.MethodPut, "/api/v1/assistant/extension-trust/qa-memory", `{"trust":"admin"}`); w.Code != http.StatusBadRequest {
		t.Errorf("an unknown trust: %d", w.Code)
	}
	if w := serve(s, http.MethodPut, "/api/v1/assistant/extension-trust/qa-memory", `{"trust":"third_party","categories":["passwords"]}`); w.Code != http.StatusBadRequest {
		t.Errorf("an unknown category: %d", w.Code)
	}
	if w := serve(s, http.MethodPut, "/api/v1/assistant/extension-trust/qa-memory", `{"trust":"third_party","categories":["health"]}`); w.Code != http.StatusOK {
		t.Fatalf("the user grants health: %d %s", w.Code, w.Body)
	}
	w := serve(s, http.MethodGet, "/api/v1/assistant/extension-trust", "")
	var trustList struct {
		Items []assistantwire.ExtensionTrust `json:"items"`
	}
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &trustList) != nil || len(trustList.Items) != 1 ||
		trustList.Items[0].Extension != "qa-memory" || trustList.Items[0].Trust != "third_party" ||
		!slices.Equal(trustList.Items[0].Categories, []string{"health"}) {
		t.Fatalf("list: %d %s", w.Code, w.Body)
	}

	if w := serve(s, http.MethodPut, "/api/v1/assistant/conversations/slee-0000-0000/categories", `{"remove":["health"]}`); w.Code != http.StatusConflict {
		t.Errorf("taking the health space's own category away: %d", w.Code)
	}
	w = serve(s, http.MethodPut, "/api/v1/assistant/conversations/main-0000-0000/categories", `{"add":["finance"]}`)
	var got assistantwire.ConversationCategories
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil || !slices.Equal(got.Categories, []string{categoryFinance}) {
		t.Fatalf("add finance: %d %s", w.Code, w.Body)
	}
	w = serve(s, http.MethodPut, "/api/v1/assistant/conversations/main-0000-0000/categories", `{"remove":["finance"]}`)
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil || len(got.Categories) != 0 || !slices.Equal(got.Removed, []string{categoryFinance}) {
		t.Fatalf("remove finance: %d %s", w.Code, w.Body)
	}
	// A model's tag does not bring back what the user took away.
	if err := s.tagConversation(context.Background(), "main-0000-0000", []string{categoryFinance}, infoTaggedByModel, ""); err != nil {
		t.Fatal(err)
	}
	if cats, _ := s.conversationCategories(context.Background(), "main-0000-0000"); len(cats) != 0 {
		t.Fatalf("after a model tag: %v", cats)
	}
}

// The profile's owners (Profile.ExtensionOwners) are read once and kept until the user sets trust or an extension is
// forgotten: a publish does not read the store each time.
func TestExtensionOwnersAreKeptBetweenTrustChanges(t *testing.T) {
	ctx := context.Background()
	s, st := trustService(t)
	if got := s.extensionOwners(ctx); len(got) != 0 {
		t.Fatalf("no trust set: %v", got)
	}
	if w := serve(s, http.MethodPut, "/api/v1/assistant/extension-trust/qa-memory", `{"trust":"owner"}`); w.Code != http.StatusOK {
		t.Fatalf("set owner: %d %s", w.Code, w.Body)
	}
	if got := s.extensionOwners(ctx); !slices.Equal(got, []string{"qa-memory"}) {
		t.Fatalf("after the user's change: %v", got)
	}
	// A row written behind the assistant's back is not read on the next publish: the owners are kept.
	_ = st.SetExtensionTrust(ctx, "qa-other", assistantwire.ExtensionTrustOwner, nil, "test")
	if got := s.extensionOwners(ctx); !slices.Equal(got, []string{"qa-memory"}) {
		t.Fatalf("kept owners: %v", got)
	}
	var none map[string]any
	if err := askConversations(t, s, assistantwire.MethodExtensionTrustForget, `{"extension":"qa-memory"}`, &none); err != nil {
		t.Fatal(err)
	}
	if got := s.extensionOwners(ctx); !slices.Equal(got, []string{"qa-other"}) {
		t.Fatalf("after a forget, read again: %v", got)
	}
}

func TestExtensionTrustHTTPRejectsStaleGrantEdits(t *testing.T) {
	database := todoTestDB(t)
	s := newTodoEnv(t, database).s
	const listPath = "/api/v1/assistant/extension-trust"
	const editPath = listPath + "/example-extension"
	read := func() (string, []assistantwire.ExtensionTrust) {
		t.Helper()
		w := serve(s, http.MethodGet, listPath, "")
		var out struct {
			DefaultRevision string                         `json:"default_revision"`
			Items           []assistantwire.ExtensionTrust `json:"items"`
		}
		if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &out) != nil || out.DefaultRevision == "" {
			t.Fatalf("read trust: %d %s", w.Code, w.Body)
		}
		return out.DefaultRevision, out.Items
	}
	save := func(body string) assistantwire.ExtensionTrust {
		t.Helper()
		w := serve(s, http.MethodPut, editPath, body)
		var out assistantwire.ExtensionTrust
		if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &out) != nil || out.Revision == "" {
			t.Fatalf("save trust: %d %s", w.Code, w.Body)
		}
		return out
	}
	edit := func(revision, categories string) string {
		return fmt.Sprintf(`{"trust":"third_party","categories":%s,"expected_revision":%q}`, categories, revision)
	}
	defaultRevision, items := read()
	if len(items) != 0 {
		t.Fatalf("initial items: %+v", items)
	}
	initial := save(edit(defaultRevision, `["finance","health"]`))
	_, first := read()
	_, second := read()
	if len(first) != 1 || len(second) != 1 || first[0].Revision != initial.Revision || second[0].Revision != initial.Revision {
		t.Fatalf("two devices read different baselines: %+v, %+v", first, second)
	}
	winner := save(edit(first[0].Revision, `["finance"]`))
	w := serve(s, http.MethodPut, editPath, edit(second[0].Revision, `["health"]`))
	var conflict struct {
		Code    string                       `json:"code"`
		Current assistantwire.ExtensionTrust `json:"current"`
	}
	if w.Code != http.StatusConflict || json.Unmarshal(w.Body.Bytes(), &conflict) != nil ||
		conflict.Code != "extension_trust_moved" || conflict.Current.Revision != winner.Revision ||
		conflict.Current.Trust != "third_party" || !slices.Equal(conflict.Current.Categories, []string{"finance"}) {
		t.Fatalf("stale edit: %d %s", w.Code, w.Body)
	}
	_, kept := read()
	if len(kept) != 1 || kept[0].Revision != winner.Revision || !slices.Equal(kept[0].Categories, []string{"finance"}) {
		t.Fatalf("stale form changed the stored grant: %+v", kept)
	}
	legacy := save(`{"trust":"third_party","categories":[]}`)
	if legacy.Revision == winner.Revision || len(legacy.Categories) != 0 {
		t.Fatalf("unversioned replacement: %+v", legacy)
	}
	if err := database.ForgetExtensionTrust(t.Context(), "example-extension"); err != nil {
		t.Fatal(err)
	}
	w = serve(s, http.MethodPut, editPath, edit(initial.Revision, `["health"]`))
	if w.Code != http.StatusConflict || json.Unmarshal(w.Body.Bytes(), &conflict) != nil ||
		conflict.Current.Revision != defaultRevision || len(conflict.Current.Categories) != 0 {
		t.Fatalf("edit after forget: %d %s", w.Code, w.Body)
	}
}
