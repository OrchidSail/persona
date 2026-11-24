package extmgr

import (
	"context"
	"encoding/json"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/extension"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/rpc"
)

// extensionPhoneControlCall handles phone.control.devices, phone.control.call, and phone.control.cancel from
// extension id. Every method requires the named-only phone.control scope (never covered by legacy all-scope tokens).
func (ps *extensions) extensionPhoneControlCall(ctx context.Context, id, method string, params json.RawMessage) (any, error) {
	if ps.reg == nil || !ps.reg.HubScopeAllowed(id, extension.ScopePhoneControl) {
		data, _ := json.Marshal(map[string]string{"scope": extension.ScopePhoneControl})
		return nil, &rpc.Error{Code: extension.CodePermissionDenied, Message: method + ": requires " + extension.ScopePhoneControl, Data: data}
	}
	var hubMethod string
	switch method {
	case "phone.control.devices":
		hubMethod = extwire.HubPhoneControlDevices
	case "phone.control.call":
		hubMethod = extwire.HubPhoneControlCall
	case "phone.control.cancel":
		hubMethod = extwire.HubPhoneControlCancel
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
