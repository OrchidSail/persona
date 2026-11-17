package assistant

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/modelhttp"
	"github.com/nwverse/persona/server/internal/proto"
)

func TestSavingStripsPastedEndpointSuffixes(t *testing.T) {
	s, _ := launcherTestService(t)
	cases := []struct {
		name string
		body string
		want string
	}{
		{
			"opencode-chat",
			`"kind":"api","api":"chat","base_url":"https://opencode.ai/inference/go/openai/v1/chat/completions"`,
			"https://opencode.ai/inference/go/openai/v1",
		},
		{
			"zen-chat-slash",
			`"kind":"api","api":"chat","base_url":"https://opencode.ai/zen/go/v1/chat/completions/"`,
			"https://opencode.ai/zen/go/v1",
		},
		{
			"bare-chat",
			`"kind":"api","api":"chat","base_url":"https://api.example.com/chat/completions"`,
			"https://api.example.com",
		},
		{
			"v1-responses",
			`"kind":"api","api":"responses","base_url":"https://api.openai.com/v1/responses"`,
			"https://api.openai.com/v1",
		},
		{
			"opencode-messages",
			`"base_url":"https://opencode.ai/inference/go/anthropic/v1/messages"`,
			"https://opencode.ai/inference/go/anthropic",
		},
	}
	for _, tc := range cases {
		body := `{"name":"` + tc.name + `",` + tc.body + `,"model":"glm-5","api_key":"` + fakeKey + `"}`
		if code, out := postLauncher(t, s, "en", body); code != 200 {
			t.Fatalf("%s: %d %v", tc.name, code, out)
		}
		if got := storedBase(t, s, tc.name); got != tc.want {
			t.Errorf("%s: stored %q, want %q", tc.name, got, tc.want)
		}
	}
}

func TestOpenCodeGoProbeCheckAndLegacyStoredEndpoint(t *testing.T) {
	s, mux := launcherTestService(t)

	const missingSessionJSON = `{"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session and cannot be routed efficiently."}}`

	var seenSessions []string
	var seenUA string
	var seenPaths []string
	interceptProbe(t, func(r *http.Request) (*http.Response, error) {
		seenPaths = append(seenPaths, r.URL.Path)
		ses := r.Header.Get(modelhttp.OpenCodeSessionHeader)
		seenSessions = append(seenSessions, ses)
		seenUA = r.Header.Get("User-Agent")

		if modelhttp.OpenCodeGo(r.URL.String()) && ses == "" {
			return probeResponse(r, http.StatusBadRequest, missingSessionJSON), nil
		}
		switch r.Method + " " + r.URL.Path {
		case "POST /inference/go/openai/v1/chat/completions", "POST /zen/go/v1/chat/completions",
			"POST /v1/chat/completions":
			return probeResponse(r, http.StatusOK, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"), nil
		case "POST /inference/go/anthropic/v1/messages", "POST /zen/go/anthropic/v1/messages":
			return probeResponse(r, http.StatusOK, `{"type":"message","content":[{"type":"text","text":"pong"}]}`), nil
		case "GET /inference/go/openai/v1/models":
			return probeResponse(r, http.StatusOK, `{"data":[{"id":"glm-5"}]}`), nil
		default:
			return probeResponse(r, http.StatusNotFound, `{"error":{"message":"not found"}}`), nil
		}
	})

	// 1. Anonymous test and discovery with a pasted full /chat/completions URL.
	pastedChat := "https://opencode.ai/inference/go/openai/v1/chat/completions"
	seenPaths, seenSessions = nil, nil
	if out := probeResult(t, mux, "/api/v1/launchers/test", `{"api":"chat","base_url":"`+pastedChat+`","api_key":"`+fakeKey+`","model":"glm-5"}`); out["ok"] != true {
		t.Fatalf("pasted chat probe failed: %v", out)
	}
	if len(seenPaths) != 1 || seenPaths[0] != "/inference/go/openai/v1/chat/completions" || seenSessions[0] == "" || seenUA != modelhttp.UserAgent() {
		t.Fatalf("paths=%v sessions=%v ua=%q", seenPaths, seenSessions, seenUA)
	}

	seenPaths, seenSessions = nil, nil
	disc := probeResult(t, mux, "/api/v1/launchers/discover", `{"api":"chat","base_url":"`+pastedChat+`","api_key":"`+fakeKey+`"}`)
	if disc["error"] != nil || len(seenPaths) != 2 || seenPaths[0] != "/inference/go/openai/v1/models" || seenPaths[1] != "/inference/go/openai/v1/model-capabilities" || seenSessions[0] == "" || seenSessions[0] != seenSessions[1] {
		t.Fatalf("discover failed: %v (paths=%v sessions=%v)", disc, seenPaths, seenSessions)
	}

	// 2. Zen Go Chat and Anthropic Messages endpoints.
	for _, tc := range []struct {
		api  string
		base string
		want string
	}{
		{"chat", "https://opencode.ai/zen/go/v1", "/zen/go/v1/chat/completions"},
		{"messages", "https://opencode.ai/inference/go/anthropic", "/inference/go/anthropic/v1/messages"},
		{"messages", "https://opencode.ai/zen/go/anthropic/v1/messages", "/zen/go/anthropic/v1/messages"},
	} {
		seenPaths, seenSessions = nil, nil
		if out := probeResult(t, mux, "/api/v1/launchers/test", `{"api":"`+tc.api+`","base_url":"`+tc.base+`","api_key":"`+fakeKey+`","model":"glm-5"}`); out["ok"] != true {
			t.Fatalf("%s (%s): %v", tc.api, tc.base, out)
		}
		if len(seenPaths) != 1 || seenPaths[0] != tc.want || seenSessions[0] == "" {
			t.Fatalf("%s (%s): paths=%v sessions=%v", tc.api, tc.base, seenPaths, seenSessions)
		}
	}

	// 3. Legacy stored provider with unstripped /chat/completions works in both /launchers/test and /launchers/{name}/check.
	sealed, err := s.sealKey(fakeKey)
	if err != nil {
		t.Fatal(err)
	}
	putLaunchers(t, s, storedLauncher{
		KeySealed: sealed,
		ManagedLauncher: proto.ManagedLauncher{
			Name:    "legacy-go",
			Kind:    "api",
			API:     "chat",
			BaseURL: pastedChat,
			Model:   "glm-5",
		},
	})
	seenPaths, seenSessions = nil, nil
	if out := probeResult(t, mux, "/api/v1/launchers/test", `{"name":"legacy-go","base_url":"https://opencode.ai/inference/go/openai/v1"}`); out["ok"] != true || len(seenSessions) != 1 || seenSessions[0] == "" {
		t.Fatalf("named probe on legacy stored URL failed: %v (sessions=%v)", out, seenSessions)
	}

	seenPaths, seenSessions = nil, nil
	code, raw := launcherCall(t, mux, http.MethodPost, "/api/v1/launchers/legacy-go/check", "")
	if code != http.StatusOK {
		t.Fatalf("check status=%d body=%s", code, raw)
	}
	var checkOut struct {
		Check proto.LauncherCheck `json:"check"`
	}
	if err := json.Unmarshal([]byte(raw), &checkOut); err != nil || !checkOut.Check.OK || len(seenSessions) != 1 || seenSessions[0] == "" || seenPaths[0] != "/inference/go/openai/v1/chat/completions" {
		t.Fatalf("check result=%+v raw=%s sessions=%v paths=%v err=%v", checkOut, raw, seenSessions, seenPaths, err)
	}

	// 4. Explicit custom header on a Messages launcher wins over the generated session ID; non-Go path gets no header.
	seenPaths, seenSessions = nil, nil
	if out := probeResult(t, mux, "/api/v1/launchers/test", `{"api":"messages","base_url":"https://opencode.ai/inference/go/anthropic","api_key":"`+fakeKey+`","model":"glm-5","headers":"x-opencode-session: explicit-ses"}`); out["ok"] != true || seenSessions[0] != "explicit-ses" {
		t.Fatalf("explicit header not preserved: out=%v sessions=%v", out, seenSessions)
	}
	seenPaths, seenSessions = nil, nil
	if out := probeResult(t, mux, "/api/v1/launchers/test", `{"api":"chat","base_url":"https://opencode.ai/v1","api_key":"`+fakeKey+`","model":"glm-5"}`); out["ok"] != true || seenSessions[0] != "" {
		t.Fatalf("non-go path received session header: out=%v sessions=%v", out, seenSessions)
	}
	if strings.Contains(raw, fakeKey) {
		t.Fatal("key leaked in check output")
	}
}
