package modelhttp

import (
	"net/http"
	"regexp"
	"testing"
)

var uuidV4Pattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func TestOpenCodeGo(t *testing.T) {
	cases := map[string]bool{
		"https://opencode.ai/inference/go/openai/v1":                  true,
		"https://opencode.ai/inference/go/openai/v1/chat/completions": true,
		"https://opencode.ai/inference/go/anthropic":                  true,
		"https://opencode.ai/inference/go/anthropic/v1/messages":      true,
		"https://opencode.ai/inference/go":                            true,
		"https://opencode.ai/zen/go":                                  true,
		"https://opencode.ai/zen/go/v1":                               true,
		"https://opencode.ai/zen/go/anthropic":                        true,
		"https://API.OpenCode.AI:443/Inference/Go/openai/v1":          true,
		"http://sub.opencode.ai:8080/zen/go/v1/chat/completions":      true,
		// Strict domain and path-segment boundaries:
		"https://notopencode.ai/inference/go/openai/v1":     false,
		"https://opencode.ai.example.com/inference/go/v1":   false,
		"https://opencode.ai/inference/good/v1":             false,
		"https://opencode.ai/zen/golang/v1":                 false,
		"https://opencode.ai/zen/v1":                        false,
		"https://opencode.ai/v1":                            false,
		"https://opencode.ai/inference/go/../v1":            false,
		"ftp://opencode.ai/inference/go/openai/v1":          false,
		"opencode.ai/inference/go/openai/v1":                false,
		"https://api.openai.com/v1":                         false,
		"https://api.anthropic.com":                         false,
		"":                                                  false,
	}
	for raw, want := range cases {
		if got := OpenCodeGo(raw); got != want {
			t.Errorf("OpenCodeGo(%q) = %v, want %v", raw, got, want)
		}
	}
}

func TestOpenCodeSessionID(t *testing.T) {
	seededA := OpenCodeSessionID("conv-123")
	seededB := OpenCodeSessionID("conv-123")
	seededOther := OpenCodeSessionID("conv-456")
	if !uuidV4Pattern.MatchString(seededA) {
		t.Fatalf("seeded id %q is not UUIDv4", seededA)
	}
	if seededA != seededB || seededA == seededOther {
		t.Fatalf("seeded ids: %q %q %q", seededA, seededB, seededOther)
	}
	randA := OpenCodeSessionID("")
	randB := OpenCodeSessionID("")
	if !uuidV4Pattern.MatchString(randA) || !uuidV4Pattern.MatchString(randB) || randA == randB {
		t.Fatalf("random ids: %q %q", randA, randB)
	}
}

func TestSetAndClearOpenCodeSession(t *testing.T) {
	h := make(http.Header)
	SetOpenCodeSession(h, "https://api.openai.com/v1/chat/completions", "ses-ignored")
	if got := h.Get(OpenCodeSessionHeader); got != "" {
		t.Fatalf("unexpected header on non-opencode host: %q", got)
	}

	SetOpenCodeSession(h, "https://opencode.ai/inference/go/openai/v1/chat/completions", "ses-1")
	if got := h.Get(OpenCodeSessionHeader); got != "ses-1" {
		t.Fatalf("got %q, want ses-1", got)
	}

	// Existing caller header (any casing) wins.
	custom := http.Header{"x-opencode-session": {"caller-supplied"}}
	SetOpenCodeSession(custom, "https://opencode.ai/zen/go/v1/chat/completions", "ses-2")
	if got := custom["x-opencode-session"]; len(got) != 1 || got[0] != "caller-supplied" {
		t.Fatalf("caller header overwritten: %v", custom)
	}

	ClearOpenCodeSession(custom)
	ClearOpenCodeSession(h)
	if len(custom) != 0 || len(h) != 0 {
		t.Fatalf("ClearOpenCodeSession left headers: %v %v", custom, h)
	}
}
