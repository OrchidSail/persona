package extension

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/i18n"
)

// agent.changed (docs/extensions/extensions.md "Agent changes") is told to the extensions that subscribed AND hold
// agents.read: the grant counts, not what the manifest declares. agents.control alone, another scope or none hears
// nothing; the legacy full grant covers agents.read, as it does on the routes.
func TestAgentChangedSubscribersNeedAgentsRead(t *testing.T) {
	r := NewRegistry(t.TempDir(), nil, nil, 1)
	for _, tc := range []struct {
		id        string
		grant     Permissions
		subscribe string
	}{
		{"read", Permissions{Hub: []string{ScopeAgentsRead}}, `"agent.changed"`},
		{"read-and-control", Permissions{Hub: []string{ScopeAgentsRead, ScopeAgentsControl}}, `"agent.changed","agent.finished"`},
		{"control", Permissions{Hub: []string{ScopeAgentsControl}}, `"agent.changed"`},
		{"declared-not-granted", Permissions{}, `"agent.changed"`},
		{"kb-only", Permissions{Hub: []string{ScopeKBRead}}, `"agent.changed"`},
		{"legacy", Permissions{HubAPI: true}, `"agent.changed"`},
		{"not-subscribed", Permissions{Hub: []string{ScopeAgentsRead}}, `"agent.finished","agent.needs_you"`},
	} {
		m := &Manifest{ID: tc.id, Version: "1.0.0", Permissions: Permissions{Hub: []string{ScopeAgentsRead}},
			Contributes: map[string]json.RawMessage{"events": json.RawMessage(`[{"id":"example","subscribe":[` + tc.subscribe + `]}]`)}}
		if err := r.AddBuiltin(m, t.TempDir(), true); err != nil {
			t.Fatal(err)
		}
		r.mu.Lock()
		r.extensions[tc.id].Record.Granted = tc.grant
		r.mu.Unlock()
	}
	if EventScopes["agent.changed"] != ScopeAgentsRead || !InEveryScope(ScopeAgentsRead) {
		t.Fatalf("agent.changed needs %q", EventScopes["agent.changed"])
	}
	want := []string{"legacy", "read", "read-and-control"}
	if got := r.EventSubscribers("agent.changed", EventScopes["agent.changed"]); !reflect.DeepEqual(got, want) {
		t.Fatalf("agent.changed: told %v, want %v", got, want)
	}
}

// Subscribing to agent.changed is asked about at install, and again when an upgrade adds it, in every language.
func TestAgentChangedIsDeclaredAndRequiresConsent(t *testing.T) {
	old := with(t, func(m map[string]any) {
		m["activation_events"] = []any{"on_startup"}
		m["contributes"] = map[string]any{"events": []any{map[string]any{"id": "agents", "subscribe": []any{"agent.finished"}}}}
	})
	next := with(t, func(m map[string]any) {
		m["activation_events"] = []any{"on_startup"}
		m["permissions"] = map[string]any{"hub": []any{"agents.read"}}
		m["contributes"] = map[string]any{"events": []any{map[string]any{"id": "agents", "subscribe": []any{"agent.finished", "agent.changed"}}}}
	})
	before, err := Parse(old)
	if err != nil {
		t.Fatal(err)
	}
	after, err := Parse(next)
	if err != nil {
		t.Fatal(err)
	}
	for _, l := range []i18n.Lang{i18n.En, i18n.ZhHans, i18n.Ja} {
		text := permissionText(l, "event_agent_changed")
		if text == "" || strings.Contains(text, "extension-permissions.") {
			t.Fatalf("%s: agent.changed has no install text: %q", l, text)
		}
		b, _ := json.Marshal(AddedIn(l, before, after))
		if !strings.Contains(string(b), text) {
			t.Fatalf("%s: the new subscription needs consent: %s", l, b)
		}
	}
}
