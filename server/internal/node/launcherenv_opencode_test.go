package node

import (
	"slices"
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

func TestCodexManagedEnvAndLaunchArgsStripLegacyResponsesSuffix(t *testing.T) {
	legacy := proto.ManagedLauncher{
		Name:    "legacy-codex",
		Agent:   "codex",
		BaseURL: "https://gw.example/v1/responses",
		APIKey:  "sk-codex",
		Model:   "gpt-5.4",
	}
	env := managedEnv(legacy)
	wantEnv := []string{"OPENAI_BASE_URL=https://gw.example/v1", "OPENAI_API_KEY=sk-codex"}
	if !slices.Equal(env, wantEnv) {
		t.Fatalf("managedEnv = %v, want %v", env, wantEnv)
	}

	argv := launchArgv("codex", []string{"exec", "hi"}, nil, "", "gpt-5.4", env, "")
	wantArgv := []string{
		"codex",
		"-c", `model_provider="persona-launcher"`,
		"-c", `model_providers.persona-launcher={name="Persona launcher",base_url="https://gw.example/v1",wire_api="responses",env_key="OPENAI_API_KEY"}`,
		"-m", "gpt-5.4",
		"exec", "hi",
	}
	if !slices.Equal(argv, wantArgv) {
		t.Fatalf("launchArgv = %v, want %v", argv, wantArgv)
	}

	catIn, ok := codexCatalogInputOf(legacy)
	if !ok || catIn.base != "https://gw.example/v1" {
		t.Fatalf("codexCatalogInputOf = %+v, %v", catIn, ok)
	}

	piProv, ok := piProviderOf(legacy)
	if !ok || piProv.BaseURL != "https://gw.example/v1" {
		t.Fatalf("piProviderOf = %+v, %v", piProv, ok)
	}
}
