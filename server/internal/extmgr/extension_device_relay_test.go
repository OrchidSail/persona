package extmgr

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/nwverse/persona/server/internal/extension"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/rpc"
)

// device.relay.* requires an exact device.relay:<channel> grant (HubScopeGranted); legacy hub_api: true,
// other scopes, and other channels are always refused before reaching the hub.
func TestExtensionDeviceRelayChannelIsolationAndNamedGrant(t *testing.T) {
	chAlpha := "alpha_ch"
	chBeta := "beta-ch"
	scopeAlpha := extension.ScopeDeviceRelayPrefix + chAlpha
	scopeBeta := extension.ScopeDeviceRelayPrefix + chBeta

	if extension.InEveryScope(scopeAlpha) || extension.InEveryScope(scopeBeta) {
		t.Fatal("device.relay:<channel> must be excluded from extension.InEveryScope")
	}

	h := newTestManager(t)
	alphaExt := kbExtensionSpec(t, h, "relay-alpha", extension.Permissions{Hub: []string{scopeAlpha}})
	kbOnly := kbExtensionSpec(t, h, "kb-only-ext", extension.Permissions{Hub: []string{extension.ScopeKBRead}})
	legacyFull := kbExtensionSpec(t, h, "legacy-full-ext", extension.Permissions{HubAPI: true})

	var mu sync.Mutex
	var gotMethods []string
	var gotExtensions []string
	th := hubOf(h)
	th.mu.Lock()
	th.app = func(method string, raw json.RawMessage) (any, error) {
		var env struct {
			Extension string `json:"extension"`
		}
		_ = json.Unmarshal(raw, &env)
		mu.Lock()
		gotMethods = append(gotMethods, method)
		gotExtensions = append(gotExtensions, env.Extension)
		mu.Unlock()
		switch method {
		case extwire.HubDeviceRelayDevices:
			return json.RawMessage(`{"devices":[]}`), nil
		case extwire.HubDeviceRelayCall:
			return json.RawMessage(`{"call_id":"dr_1","device_id":"dev_1","channel":"alpha_ch","ok":true,"result":{"ok":true}}`), nil
		case extwire.HubDeviceRelayCancel:
			return json.RawMessage(`{"cancelled":true}`), nil
		}
		return map[string]any{}, nil
	}
	th.mu.Unlock()

	alphaCalls := []struct {
		method string
		params json.RawMessage
		hub    string
	}{
		{"device.relay.devices", json.RawMessage(`{"channel":"alpha_ch"}`), extwire.HubDeviceRelayDevices},
		{"device.relay.call", json.RawMessage(`{"device_id":"dev_1","channel":"alpha_ch","payload":{"ping":1}}`), extwire.HubDeviceRelayCall},
		{"device.relay.cancel", json.RawMessage(`{"device_id":"dev_1","channel":"alpha_ch","call_id":"dr_1"}`), extwire.HubDeviceRelayCancel},
	}

	// 1. Denied for kb.read and legacy hub_api: true.
	for name, fn := range map[string]func(context.Context, string, json.RawMessage) (any, error){
		"kb.read":  kbOnly.Handle,
		"full-hub": legacyFull.Handle,
	} {
		for _, tc := range alphaCalls {
			_, err := fn(context.Background(), tc.method, tc.params)
			var re *rpc.Error
			if !errors.As(err, &re) || re.Code != extension.CodePermissionDenied || !strings.Contains(string(re.Data), `"`+scopeAlpha+`"`) {
				t.Fatalf("%s %s: got %v, want permission_denied(%s)", name, tc.method, err, scopeAlpha)
			}
		}
	}

	// 2. Cross-channel isolation: alphaExt granted device.relay:alpha_ch cannot list, call, or cancel beta-ch.
	for _, tc := range []struct {
		method string
		params json.RawMessage
	}{
		{"device.relay.devices", json.RawMessage(`{"channel":"beta-ch"}`)},
		{"device.relay.call", json.RawMessage(`{"device_id":"dev_1","channel":"beta-ch","payload":{}}`)},
		{"device.relay.cancel", json.RawMessage(`{"device_id":"dev_1","channel":"beta-ch","call_id":"dr_1"}`)},
	} {
		_, err := alphaExt.Handle(context.Background(), tc.method, tc.params)
		var re *rpc.Error
		if !errors.As(err, &re) || re.Code != extension.CodePermissionDenied || !strings.Contains(string(re.Data), `"`+scopeBeta+`"`) {
			t.Fatalf("cross-channel %s: got %v, want permission_denied(%s)", tc.method, err, scopeBeta)
		}
	}

	// 3. Invalid or missing channel rejected with invalid_params before hitting Hub.
	for _, bad := range []json.RawMessage{
		nil,
		json.RawMessage(`{}`),
		json.RawMessage(`null`),
		json.RawMessage(`{"channel":""}`),
		json.RawMessage(`{"channel":"BadUpper"}`),
		json.RawMessage(`{"channel":"bad.dot"}`),
		json.RawMessage(`{"channel":"-leading"}`),
		json.RawMessage(`{"channel":" alpha_ch"}`),
		json.RawMessage(`{"channel":"` + strings.Repeat("a", 33) + `"}`),
		json.RawMessage(`not-json`),
	} {
		_, err := alphaExt.Handle(context.Background(), "device.relay.devices", bad)
		var re *rpc.Error
		if !errors.As(err, &re) || re.Code != "invalid_params" {
			t.Fatalf("bad channel %s: got %v, want invalid_params", string(bad), err)
		}
	}

	if len(gotMethods) != 0 {
		t.Fatalf("denied/invalid calls reached hub: %v", gotMethods)
	}

	// 4. Allowed when exact channel scope is granted; caller extension id is forwarded.
	for _, tc := range alphaCalls {
		if _, err := alphaExt.Handle(context.Background(), tc.method, tc.params); err != nil {
			t.Fatalf("granted %s: %v", tc.method, err)
		}
	}
	if len(gotMethods) != 3 || gotMethods[0] != extwire.HubDeviceRelayDevices || gotMethods[1] != extwire.HubDeviceRelayCall || gotMethods[2] != extwire.HubDeviceRelayCancel {
		t.Fatalf("hub methods = %v, want devices/call/cancel", gotMethods)
	}
	for i, extID := range gotExtensions {
		if extID != "relay-alpha" {
			t.Fatalf("forwarded extension[%d] = %q, want relay-alpha", i, extID)
		}
	}
}
