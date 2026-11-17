package node

import (
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/modelhttp"
	"github.com/nwverse/persona/server/internal/proto"
)

func TestProviderEnvOpenCodeGoCustomHeaders(t *testing.T) {
	l := proto.ManagedLauncher{
		Name:    "opencode-claude",
		BaseURL: "https://opencode.ai/inference/go/anthropic/v1/messages",
		APIKey:  "sk-test",
		Model:   "claude-sonnet-5-5",
	}
	envA := providerEnv(l, "", "", false)
	envB := providerEnv(l, "", "", false)
	if envA["ANTHROPIC_BASE_URL"] != "https://opencode.ai/inference/go/anthropic" {
		t.Fatalf("ANTHROPIC_BASE_URL = %q", envA["ANTHROPIC_BASE_URL"])
	}
	want := modelhttp.OpenCodeSessionHeader + ": " + modelhttp.OpenCodeSessionID("launcher:opencode-claude")
	if envA["ANTHROPIC_CUSTOM_HEADERS"] != want || envB["ANTHROPIC_CUSTOM_HEADERS"] != want {
		t.Fatalf("ANTHROPIC_CUSTOM_HEADERS = %q / %q, want %q", envA["ANTHROPIC_CUSTOM_HEADERS"], envB["ANTHROPIC_CUSTOM_HEADERS"], want)
	}

	// Appends cleanly when other custom headers exist without x-opencode-session.
	withTeam := l
	withTeam.Headers = "X-Team: alpha"
	if got := providerEnv(withTeam, "", "", false)["ANTHROPIC_CUSTOM_HEADERS"]; got != "X-Team: alpha\n"+want {
		t.Fatalf("merged headers = %q", got)
	}

	// Preserves user-supplied x-opencode-session.
	withExplicit := l
	withExplicit.Headers = "X-OpenCode-Session: ses_custom"
	if got := providerEnv(withExplicit, "", "", false)["ANTHROPIC_CUSTOM_HEADERS"]; got != "X-OpenCode-Session: ses_custom" {
		t.Fatalf("explicit header overwritten: %q", got)
	}

	// Non-OpenCode gateways stay untouched.
	other := proto.ManagedLauncher{Name: "other", BaseURL: "https://notopencode.ai/inference/go/anthropic", APIKey: "sk-test", Model: "m"}
	if got := providerEnv(other, "", "", false)["ANTHROPIC_CUSTOM_HEADERS"]; strings.Contains(got, modelhttp.OpenCodeSessionHeader) {
		t.Fatalf("unexpected header on non-opencode gateway: %q", got)
	}
}
