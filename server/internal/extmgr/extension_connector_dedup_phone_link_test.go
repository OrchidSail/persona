package extmgr

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/connector"
	"github.com/nwverse/persona/server/internal/connector/proc"
	"github.com/nwverse/persona/server/internal/extension"
	"github.com/nwverse/persona/server/internal/extension/host"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/proto"
)

type stubBuiltinConnector struct {
	id   string
	name string
}

func (s stubBuiltinConnector) ID() string   { return s.id }
func (s stubBuiltinConnector) Name() string { return s.name }
func (s stubBuiltinConnector) Manifest() connector.Manifest {
	return connector.Manifest{Capabilities: []connector.Capability{connector.CapStatus, connector.CapTools}}
}
func (s stubBuiltinConnector) Status(context.Context) connector.Status {
	return connector.Status{State: connector.StateOK}
}
func (s stubBuiltinConnector) Tools() []proto.Tool {
	return []proto.Tool{{Name: s.id + "_read"}}
}
func (s stubBuiltinConnector) Call(context.Context, string, json.RawMessage) (string, error) {
	return "ok", nil
}

func TestListedConnectorsPopulatesExtensionIDAndPreservesTools(t *testing.T) {
	h := newTestManager(t)
	h.connectors = connector.NewRegistry(nil)
	ps := newExtensions(h.dataDirOrTemp(), nil, h.logf)
	ps.h = h
	h.extensions = ps

	builtin := stubBuiltinConnector{id: "examplebuiltin", name: "Built-in"}
	if err := h.connectors.Add(builtin); err != nil {
		t.Fatal(err)
	}

	extConn := proc.New(proc.Config{
		ID:   "personaphone",
		Name: "Persona Phone Control",
		Manifest: connector.Manifest{
			Capabilities: []connector.Capability{connector.CapStatus, connector.CapTools, connector.CapActions},
			AgentTools:   []string{"personaphone_*"},
		},
		Link: &proc.Link{ID: "personaphone"},
	})
	if err := h.connectors.Add(extConn); err != nil {
		t.Fatal(err)
	}
	ps.mu.Lock()
	ps.byID["persona-phone"] = &hubExtension{id: "persona-phone", conns: []*proc.Conn{extConn}}
	ps.mu.Unlock()

	listed := h.listedConnectors(context.Background(), true)
	byID := map[string]connector.Info{}
	for _, info := range listed {
		byID[info.ID] = info
	}
	if got := byID["personaphone"].ExtensionID; got != "persona-phone" {
		t.Fatalf("personaphone extension_id = %q, want persona-phone", got)
	}
	if got := byID["examplebuiltin"].ExtensionID; got != "" {
		t.Fatalf("builtin extension_id = %q, want empty", got)
	}
	if !slices.Equal(byID["personaphone"].Capabilities, []string{"status", "tools", "actions"}) {
		t.Fatalf("personaphone capabilities = %v", byID["personaphone"].Capabilities)
	}
	// Verify JSON omits extension_id on built-ins and includes it on extension-hosted connectors.
	raw, err := json.Marshal(listed)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"extension_id":"persona-phone"`) {
		t.Fatalf("missing extension_id in JSON: %s", raw)
	}
	// Verify connector registration and tool prefix ownership remain intact.
	if owner := h.connectors.Owner("personaphone_run_task"); owner == nil || owner.ID() != "personaphone" {
		t.Fatalf("personaphone tool owner = %v, want personaphone", owner)
	}
	if !h.connectors.ForAgents("personaphone_run_task") {
		t.Fatal("expected personaphone_* agent_tools matching to remain intact")
	}
}

func TestCheckOpenURLAllowsPhoneConnectorSettingsWhitelistOnly(t *testing.T) {
	page := extension.UIItem{ID: "persona-phone", Placement: "extension_page"}
	if err := checkOpenURL("persona-phone", page, "", extension.PhoneConnectorSettingsURL); err != nil {
		t.Fatalf("expected %s to be allowed: %v", extension.PhoneConnectorSettingsURL, err)
	}
	if err := checkOpenURL("persona-phone", page, "", extension.SettingsURL("persona-phone")); err != nil {
		t.Fatalf("expected own extension settings URL to remain allowed: %v", err)
	}
	if err := checkOpenURL("persona-phone", page, "", "https://example.com/docs"); err != nil {
		t.Fatalf("expected https URL to remain allowed: %v", err)
	}
	for _, bad := range []string{
		"persona://settings/connectors/phone/",
		"persona://settings/connectors/phone?tab=safety",
		"persona://settings/connectors/phone#section",
		"persona://settings/connectors/phone/extra",
		"persona://settings/connectors/phone/../mail",
		"persona://settings/connectors/mail",
		"persona://settings/connectors",
		"persona://user@settings/connectors/phone",
		"jarvis://settings/connectors/phone",
		"http://settings/connectors/phone",
		"javascript:alert(1)",
		"intent://settings/connectors/phone",
		"file:///etc/passwd",
		"tel:+15550100",
	} {
		if err := checkOpenURL("persona-phone", page, "", bad); err == nil {
			t.Errorf("expected unauthorized URL %q to be rejected", bad)
		}
	}
}

func TestUIParamsViewerDeviceIDPassthrough(t *testing.T) {
	h := newTestManager(t)
	ps := newExtensions(h.dataDirOrTemp(), nil, h.logf)
	ps.h = h
	h.extensions = ps

	uiRaw, err := json.Marshal([]extension.UIItem{{ID: "home", Title: "Home", Placement: "extension_page"}})
	if err != nil {
		t.Fatal(err)
	}
	m := &extension.Manifest{
		ManifestVersion: extension.ManifestVersion,
		ID:              "example-phone",
		Name:            "Example Phone",
		Version:         "1.0.0",
		Engines:         extension.Engines{ExtensionProtocol: extension.Protocol},
		Contributes:     map[string]json.RawMessage{"ui": uiRaw},
	}
	if err := ps.reg.AddBuiltin(m, t.TempDir(), true); err != nil {
		t.Fatal(err)
	}
	ps.mu.Lock()
	ps.byID["example-phone"] = &hubExtension{id: "example-phone", proc: &host.Proc{}}
	ps.mu.Unlock()

	callWith := func(ctx context.Context) extension.UIParams {
		t.Helper()
		var got extension.UIParams
		_, err := h.withUI(ctx, "example-phone", "home", uiRequest{}, func(_ *extension.Extension, _ *hubExtension, _ extension.UIItem, _ extension.UISlot, params extension.UIParams) (extension.ActionResult, error) {
			got = params
			return extension.ActionResult{}, nil
		})
		if err != nil {
			t.Fatalf("withUI failed: %v", err)
		}
		return got
	}

	// Signed-in device request (as relayed from hub with X-Persona-Device: dev_<12hex>).
	req := httptest.NewRequest(http.MethodPost, "/api/v1/extensions/example-phone/-/ui/home", nil)
	req.Header.Set(extwire.HeaderDevice, "dev_0123456789ab")
	req.Header.Set(extwire.HeaderRole, roleFull)
	deviceCtx := withPrincipal(context.Background(), principalFrom(req))
	gotDevice := callWith(deviceCtx)
	if gotDevice.ViewerDeviceID != "dev_0123456789ab" {
		t.Fatalf("ViewerDeviceID = %q, want dev_0123456789ab", gotDevice.ViewerDeviceID)
	}
	rawDevice, err := json.Marshal(gotDevice)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(rawDevice), `"viewer_device_id":"dev_0123456789ab"`) {
		t.Fatalf("expected viewer_device_id in JSON: %s", rawDevice)
	}

	// Non-device / unidentified callers omit viewer_device_id for backward compatibility.
	for name, p := range map[string]principal{
		"master_token":    {Role: roleFull, Owner: true},
		"pairing_token":   {ID: "dev_0123456789ab", Role: roleFull, Paired: true},
		"extension_token": {ID: "dev_0123456789ab", Role: roleFull, Extension: "other"},
		"answer_only":     {ID: "dev_0123456789ab", Role: roleAnswer},
		"non_device_id":   {ID: "owner", Role: roleFull},
		"pair_prefix_id":  {ID: "pair_abcdef", Role: roleFull},
	} {
		got := callWith(withPrincipal(context.Background(), p))
		if got.ViewerDeviceID != "" {
			t.Errorf("%s: ViewerDeviceID = %q, want empty", name, got.ViewerDeviceID)
		}
		raw, err := json.Marshal(got)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(raw), "viewer_device_id") {
			t.Errorf("%s: expected viewer_device_id to be omitted in JSON: %s", name, raw)
		}
	}
}
