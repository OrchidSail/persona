package extmgr

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/extension"
)

// agent.changed, as the hub's extensions.emit brings it, reaches a running extension only when it subscribed to the
// topic and was granted agents.read (or the legacy full grant, which covers that scope): subscribed with another
// scope, with agents.control alone or with none, it hears nothing; so does a reader that did not subscribe.
func TestAgentChangedReachesOnlyAuthorizedExtensions(t *testing.T) {
	for _, tc := range []struct {
		name, permissions string
		subscribe         []string
		allowed           bool
	}{
		{"reader", `{"hub":["agents.read"]}`, []string{"agent.changed", "agent.finished"}, true},
		{"legacy", `{"hub_api":true}`, []string{"agent.changed", "agent.finished"}, true},
		{"controller", `{"hub":["agents.control"]}`, []string{"agent.changed", "agent.finished"}, false},
		{"kb-reader", `{"hub":["kb.read"]}`, []string{"agent.changed", "agent.finished"}, false},
		{"none", `{}`, []string{"agent.changed", "agent.finished"}, false},
		{"reader-not-subscribed", `{"hub":["agents.read"]}`, []string{"agent.finished"}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h, _ := extensionTestHub(t)
			mux := h.routes()
			token := h.cfg.Token
			dir := uiPackage(t)
			path := filepath.Join(dir, extension.ManifestFile)
			raw, _ := os.ReadFile(path)
			var manifest map[string]any
			_ = json.Unmarshal(raw, &manifest)
			var permissions map[string]any
			_ = json.Unmarshal([]byte(tc.permissions), &permissions)
			permissions["launcher_keys"] = true
			manifest["permissions"] = permissions
			manifest["contributes"].(map[string]any)["events"] = []any{map[string]any{"id": "agents", "subscribe": tc.subscribe}}
			raw, _ = json.Marshal(manifest)
			if err := os.WriteFile(path, raw, 0600); err != nil {
				t.Fatal(err)
			}
			code, pv := call(t, mux, "POST", "/api/v1/extensions/-/preview", token, `{"source":"dir:`+dir+`"}`)
			if code != 200 {
				t.Fatalf("preview %d %v", code, pv)
			}
			code, out := call(t, mux, "POST", "/api/v1/extensions/-/install", token, fmt.Sprintf(`{"preview_id":%q,"permissions_digest":%q}`, pv["preview_id"], pv["permissions_digest"]))
			if code != 200 {
				t.Fatalf("install %d %v", code, out)
			}
			waitLong(t, "agent.changed subscriber running", func() bool { p := h.extensions.get("example-ui"); return p != nil && p.proc != nil && p.proc.Ready() })
			view := func() string {
				_, out := call(t, mux, "POST", "/api/v1/extensions/example-ui/-/ui/home", token, `{}`)
				return fmt.Sprint(out["view"])
			}
			// As the hub sends it (extensions.emit), then a sentinel behind it on the same process stream: once the
			// sentinel is counted, everything the extension is going to be told has been read.
			b1Emit(h, "agent.changed", map[string]any{"machine_id": "example-machine", "session_id": "example-session", "ended": true})
			b1Emit(h, "agent.finished", map[string]any{})
			if !tc.allowed {
				waitLong(t, "sentinel event delivered", func() bool { return strings.Contains(view(), "value:1") })
				if v := view(); strings.Contains(v, "agent.changed") || strings.Contains(v, "example-session") {
					t.Fatalf("agent.changed reached an extension that is not allowed it: %s", v)
				}
				return
			}
			waitLong(t, "agent.changed delivered", func() bool { return strings.Contains(view(), "value:2") })
			if v := view(); !strings.Contains(v, `<agent.changed {"ended":true,"machine_id":"example-machine","session_id":"example-session"}>`) {
				t.Fatalf("agent.changed as the extension was told it: %s", v)
			}
		})
	}
}
