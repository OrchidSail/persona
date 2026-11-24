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

// phone.control.* requires the named-only phone.control scope; legacy full-hub (hub_api: true) grants never include it.
func TestExtensionPhoneControlNamedOnlyGrant(t *testing.T) {
	if extension.InEveryScope(extension.ScopePhoneControl) {
		t.Fatalf("ScopePhoneControl must be excluded from extension.InEveryScope")
	}
	h := newTestManager(t)
	granted := kbExtensionSpec(t, h, "phone-ctrl-ext", extension.Permissions{Hub: []string{extension.ScopePhoneControl}})
	kbOnly := kbExtensionSpec(t, h, "kb-only-ext", extension.Permissions{Hub: []string{extension.ScopeKBRead}})
	legacyFull := kbExtensionSpec(t, h, "legacy-full-ext", extension.Permissions{HubAPI: true})

	var mu sync.Mutex
	var gotMethods []string
	th := hubOf(h)
	th.mu.Lock()
	th.app = func(method string, _ json.RawMessage) (any, error) {
		mu.Lock()
		gotMethods = append(gotMethods, method)
		mu.Unlock()
		switch method {
		case extwire.HubPhoneControlDevices:
			return json.RawMessage(`{"devices":[]}`), nil
		case extwire.HubPhoneControlCall:
			return json.RawMessage(`{"call_id":"pc_1","device_id":"dev_1","ok":true,"result":{"ready":true}}`), nil
		case extwire.HubPhoneControlCancel:
			return json.RawMessage(`{"cancelled":true}`), nil
		}
		return map[string]any{}, nil
	}
	th.mu.Unlock()

	calls := []struct {
		method string
		params json.RawMessage
		hub    string
	}{
		{"phone.control.devices", json.RawMessage(`{"include_not_ready":true}`), extwire.HubPhoneControlDevices},
		{"phone.control.call", json.RawMessage(`{"device_id":"dev_1","op":"device_state"}`), extwire.HubPhoneControlCall},
		{"phone.control.cancel", json.RawMessage(`{"device_id":"dev_1","call_id":"pc_1"}`), extwire.HubPhoneControlCancel},
	}

	// Denied for kb.read and legacy hub_api: true.
	for name, fn := range map[string]func(context.Context, string, json.RawMessage) (any, error){
		"kb.read":  kbOnly.Handle,
		"full-hub": legacyFull.Handle,
	} {
		for _, tc := range calls {
			_, err := fn(context.Background(), tc.method, tc.params)
			var re *rpc.Error
			if !errors.As(err, &re) || re.Code != extension.CodePermissionDenied || !strings.Contains(string(re.Data), `"phone.control"`) {
				t.Fatalf("%s %s: got %v, want permission_denied(phone.control)", name, tc.method, err)
			}
		}
	}
	if len(gotMethods) != 0 {
		t.Fatalf("denied calls reached hub: %v", gotMethods)
	}

	// Allowed when phone.control is granted by name.
	for _, tc := range calls {
		if _, err := granted.Handle(context.Background(), tc.method, tc.params); err != nil {
			t.Fatalf("granted %s: %v", tc.method, err)
		}
	}
	if len(gotMethods) != 3 || gotMethods[0] != extwire.HubPhoneControlDevices || gotMethods[1] != extwire.HubPhoneControlCall || gotMethods[2] != extwire.HubPhoneControlCancel {
		t.Fatalf("hub methods = %v, want devices/call/cancel", gotMethods)
	}
}
