package extmgr

import (
	"context"
	"encoding/json"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/extension"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/rpc"
)

// extensionDeviceRelayCall handles device.relay.devices, device.relay.call, and device.relay.cancel from extension id.
// Every method requires the exact parameterized scope device.relay:<channel> explicitly granted to the extension
// (HubScopeGranted only; never satisfied by legacy hub_api: true or another channel's grant).
func (ps *extensions) extensionDeviceRelayCall(ctx context.Context, id, method string, params json.RawMessage) (any, error) {
	var target struct {
		Channel string `json:"channel"`
	}
	if len(params) == 0 {
		return nil, &rpc.Error{Code: "invalid_params", Message: method + ": params must be a JSON object with channel"}
	}
	if err := json.Unmarshal(params, &target); err != nil {
		return nil, &rpc.Error{Code: "invalid_params", Message: method + ": " + err.Error()}
	}
	if !extension.ValidID(target.Channel) {
		return nil, &rpc.Error{Code: "invalid_params", Message: method + ": invalid channel"}
	}
	scope := extension.ScopeDeviceRelayPrefix + target.Channel
	if ps.reg == nil || !ps.reg.HubScopeGranted(id, scope) {
		data, _ := json.Marshal(map[string]string{"scope": scope})
		return nil, &rpc.Error{Code: extension.CodePermissionDenied, Message: method + ": requires " + scope, Data: data}
	}
	var hubMethod string
	switch method {
	case "device.relay.devices":
		hubMethod = extwire.HubDeviceRelayDevices
	case "device.relay.call":
		hubMethod = extwire.HubDeviceRelayCall
	case "device.relay.cancel":
		hubMethod = extwire.HubDeviceRelayCancel
	default:
		return nil, &rpc.Error{Code: extension.CodeUnknownMethod, Message: "unknown method " + method}
	}
	if ps.h == nil {
		return nil, &rpc.Error{Code: assistantwire.CodeAssistantOffline, Message: errAssistantOffline.Error()}
	}
	req := struct {
		Extension string          `json:"extension"`
		Request   json.RawMessage `json:"request,omitempty"`
	}{Extension: id, Request: params}
	var out json.RawMessage
	if err := ps.h.callHub(ctx, hubMethod, req, &out); err != nil {
		return nil, err
	}
	return out, nil
}
