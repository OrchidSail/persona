package hub

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/nwverse/persona/sdk/go/wire"
	"github.com/nwverse/persona/server/internal/rpc"
)

// phoneControlRegistration is the in-memory remote phone control state reported by a paired full-access phone over
// /api/v1/ws (register_phone_control). It stays strictly separate from register_phone / h.phoneTools / db.Store so
// low-level screen control ops never leak into the assistant's ordinary phone tool catalog.
type phoneControlRegistration struct {
	ProtocolVersion int
	DeviceID        string
	DeviceName      string
	Manufacturer    string
	Model           string
	Platform        string
	AndroidRelease  string
	AndroidSDK      int
	AppVersion      string
	Enabled         bool
	Ready           bool
	Capabilities    []string
	Permissions     wire.PhoneControlPermissions
	SafetyPolicy    wire.PhoneControlSafetyPolicy
	Runtime         wire.PhoneControlRuntimeState
	ConnectedSince  time.Time
	LastSeen        time.Time
}

type phoneControlWireResult struct {
	OK          bool
	Result      json.RawMessage
	Error       *wire.PhoneControlError
	internalErr *rpc.Error
}

type phoneControlPending struct {
	client   *client
	deviceID string
	ch       chan phoneControlWireResult
}

type phoneControlCallFrame struct {
	Type           string          `json:"type"`
	CallID         string          `json:"call_id"`
	IdempotencyKey string          `json:"idempotency_key,omitempty"`
	Op             string          `json:"op"`
	DeadlineMs     int             `json:"deadline_ms"`
	Args           json.RawMessage `json:"args"`
}

type phoneControlCancelFrame struct {
	Type   string `json:"type"`
	CallID string `json:"call_id"`
	Reason string `json:"reason"`
}

const (
	phoneControlMaxTimeoutMs    = 60_000
	phoneControlSeenMaxEntries  = 1024
	phoneControlDefaultPlatform = "android"
)

func (h *Hub) onRegisterPhoneControl(c *client, data []byte) {
	if c.role != roleFull {
		return
	}
	var msg struct {
		ProtocolVersion int                           `json:"protocol_version"`
		DeviceID        string                        `json:"device_id"`
		DeviceName      string                        `json:"device_name"`
		Manufacturer    string                        `json:"manufacturer"`
		Model           string                        `json:"model"`
		Platform        string                        `json:"platform"`
		AndroidRelease  string                        `json:"android_release"`
		AndroidSDK      int                           `json:"android_sdk"`
		AppVersion      string                        `json:"app_version"`
		Enabled         bool                          `json:"enabled"`
		Ready           bool                          `json:"ready"`
		Capabilities    []string                      `json:"capabilities"`
		Permissions     wire.PhoneControlPermissions  `json:"permissions"`
		SafetyPolicy    wire.PhoneControlSafetyPolicy `json:"safety_policy"`
		Runtime         wire.PhoneControlRuntimeState `json:"runtime"`
	}
	if json.Unmarshal(data, &msg) != nil {
		return
	}
	now := time.Now().UTC()
	h.mu.Lock()
	defer h.mu.Unlock()
	if !msg.Enabled {
		c.phoneControl = nil
		h.failClientPhoneControlCallsLocked(c, "control_disabled", "remote phone control was disabled on target phone")
		return
	}
	deviceID := strings.TrimSpace(c.device)
	if deviceID == "" {
		deviceID = strings.TrimSpace(msg.DeviceID)
	}
	if deviceID == "" {
		return
	}
	platform := strings.TrimSpace(msg.Platform)
	if platform == "" {
		platform = phoneControlDefaultPlatform
	}
	name := strings.TrimSpace(msg.DeviceName)
	if name == "" {
		name = strings.TrimSpace(c.name)
	}
	if name == "" {
		name = strings.TrimSpace(strings.TrimSpace(msg.Manufacturer + " " + msg.Model))
	}
	if name == "" {
		name = deviceID
	}
	connectedSince := now
	if c.phoneControl != nil && !c.phoneControl.ConnectedSince.IsZero() {
		connectedSince = c.phoneControl.ConnectedSince
	} else if !c.since.IsZero() {
		connectedSince = c.since.UTC()
	}
	protoVersion := msg.ProtocolVersion
	if protoVersion <= 0 {
		protoVersion = 1
	}
	caps := slices.Clone(msg.Capabilities)
	if caps == nil {
		caps = []string{}
	}
	c.phoneControl = &phoneControlRegistration{
		ProtocolVersion: protoVersion,
		DeviceID:        deviceID,
		DeviceName:      name,
		Manufacturer:    strings.TrimSpace(msg.Manufacturer),
		Model:           strings.TrimSpace(msg.Model),
		Platform:        platform,
		AndroidRelease:  strings.TrimSpace(msg.AndroidRelease),
		AndroidSDK:      msg.AndroidSDK,
		AppVersion:      strings.TrimSpace(msg.AppVersion),
		Enabled:         true,
		Ready:           msg.Ready,
		Capabilities:    caps,
		Permissions:     msg.Permissions,
		SafetyPolicy:    msg.SafetyPolicy,
		Runtime:         msg.Runtime,
		ConnectedSince:  connectedSince,
		LastSeen:        now,
	}
	if h.phoneControlSeen == nil {
		h.phoneControlSeen = map[string]bool{}
	}
	if len(h.phoneControlSeen) >= phoneControlSeenMaxEntries && !h.phoneControlSeen[deviceID] {
		clear(h.phoneControlSeen)
	}
	h.phoneControlSeen[deviceID] = true
}

func (h *Hub) onPhoneControlResult(c *client, data []byte) {
	if c.role != roleFull {
		return
	}
	var msg struct {
		CallID string                  `json:"call_id"`
		OK     bool                    `json:"ok"`
		Result json.RawMessage         `json:"result"`
		Error  *wire.PhoneControlError `json:"error"`
	}
	if json.Unmarshal(data, &msg) != nil || strings.TrimSpace(msg.CallID) == "" {
		return
	}
	h.mu.Lock()
	pending := h.phoneControlCalls[msg.CallID]
	if pending == nil || pending.client != c {
		h.mu.Unlock()
		return
	}
	delete(h.phoneControlCalls, msg.CallID)
	if c.phoneControl != nil {
		c.phoneControl.LastSeen = time.Now().UTC()
	}
	h.mu.Unlock()
	res := phoneControlWireResult{
		OK:     msg.OK,
		Result: bytes.Clone(msg.Result),
		Error:  msg.Error,
	}
	if len(bytes.TrimSpace(res.Result)) == 0 || bytes.Equal(bytes.TrimSpace(res.Result), []byte("null")) {
		res.Result = nil
	}
	select {
	case pending.ch <- res:
	default:
	}
}

// failClientPhoneControlCallsLocked fails and removes all in-flight phone control calls bound to c. Caller holds h.mu.
func (h *Hub) failClientPhoneControlCallsLocked(c *client, code, message string) {
	for id, pending := range h.phoneControlCalls {
		if pending.client != c {
			continue
		}
		delete(h.phoneControlCalls, id)
		select {
		case pending.ch <- phoneControlWireResult{internalErr: &rpc.Error{Code: code, Message: message}}:
		default:
		}
	}
}

func decodeStrictPhoneControlJSON(raw json.RawMessage, dst any) error {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return nil
	}
	dec := json.NewDecoder(bytes.NewReader(trimmed))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("unexpected trailing JSON data")
	}
	return nil
}

func clientClosed(c *client) bool {
	if c.done == nil {
		return false
	}
	select {
	case <-c.done:
		return true
	default:
		return false
	}
}

func phoneControlDeviceID(c *client) string {
	if id := strings.TrimSpace(c.device); id != "" {
		return id
	}
	if c.phoneControl != nil {
		return strings.TrimSpace(c.phoneControl.DeviceID)
	}
	return ""
}

func phoneControlNewer(a, b *client) bool {
	if !a.since.Equal(b.since) {
		return a.since.After(b.since)
	}
	if a.phoneControl != nil && b.phoneControl != nil && !a.phoneControl.LastSeen.Equal(b.phoneControl.LastSeen) {
		return a.phoneControl.LastSeen.After(b.phoneControl.LastSeen)
	}
	return a.activeAt.After(b.activeAt)
}

func (h *Hub) hubPhoneControlDevices(raw json.RawMessage) (wire.PhoneControlDevicesResult, error) {
	var p wire.PhoneControlDevicesParams
	if err := decodeStrictPhoneControlJSON(raw, &p); err != nil {
		return wire.PhoneControlDevicesResult{}, &rpc.Error{Code: "invalid_params", Message: err.Error()}
	}
	type candidate struct {
		client *client
		reg    phoneControlRegistration
		sim    bool
		device wire.PhoneControlDevice
	}
	h.mu.Lock()
	primaryCred := h.primaryPhone.CredentialID
	bestByID := map[string]*candidate{}
	for c := range h.clients {
		if clientClosed(c) || c.phoneControl == nil || !c.phoneControl.Enabled {
			continue
		}
		devID := phoneControlDeviceID(c)
		if devID == "" {
			continue
		}
		if prev, ok := bestByID[devID]; ok && !phoneControlNewer(c, prev.client) {
			continue
		}
		reg := *c.phoneControl
		caps := slices.Clone(reg.Capabilities)
		if caps == nil {
			caps = []string{}
		}
		credID := strings.TrimSpace(c.device)
		bestByID[devID] = &candidate{
			client: c,
			reg:    reg,
			sim:    c.sim,
			device: wire.PhoneControlDevice{
				DeviceID:        devID,
				CredentialID:    credID,
				Name:            reg.DeviceName,
				Manufacturer:    reg.Manufacturer,
				Model:           reg.Model,
				Platform:        reg.Platform,
				AndroidRelease:  reg.AndroidRelease,
				AndroidSdk:      reg.AndroidSDK,
				AppVersion:      reg.AppVersion,
				Online:          true,
				Enabled:         reg.Enabled,
				Ready:           reg.Ready,
				Primary:         primaryCred != "" && credID == primaryCred,
				ProtocolVersion: reg.ProtocolVersion,
				Capabilities:    caps,
				Permissions:     reg.Permissions,
				SafetyPolicy:    reg.SafetyPolicy,
				Runtime:         reg.Runtime,
				ConnectedSince:  reg.ConnectedSince.UTC(),
				LastSeen:        reg.LastSeen.UTC(),
			},
		}
	}
	h.mu.Unlock()

	list := make([]*candidate, 0, len(bestByID))
	for _, cand := range bestByID {
		if !p.IncludeNotReady && !cand.device.Ready {
			continue
		}
		list = append(list, cand)
	}
	sort.Slice(list, func(i, j int) bool {
		a, b := list[i], list[j]
		if a.sim != b.sim {
			return !a.sim
		}
		if a.device.Primary != b.device.Primary {
			return a.device.Primary
		}
		if a.device.Ready != b.device.Ready {
			return a.device.Ready
		}
		if !a.device.LastSeen.Equal(b.device.LastSeen) {
			return a.device.LastSeen.After(b.device.LastSeen)
		}
		return a.device.DeviceID < b.device.DeviceID
	})

	devices := make([]wire.PhoneControlDevice, 0, len(list))
	for _, cand := range list {
		devices = append(devices, cand.device)
	}
	return wire.PhoneControlDevicesResult{Devices: devices}, nil
}

func phoneControlTimeouts(op string, requestedMs int) (timeoutMs, deadlineMs int, ok bool) {
	switch op {
	case "device_state", "session", "otp_consume":
		timeoutMs, deadlineMs = 8_000, 5_000
	case "observe":
		timeoutMs, deadlineMs = 15_000, 12_000
	case "action":
		timeoutMs, deadlineMs = 20_000, 16_000
	default:
		return 0, 0, false
	}
	if requestedMs > 0 {
		timeoutMs = min(requestedMs, phoneControlMaxTimeoutMs)
		if timeoutMs > 2_000 {
			deadlineMs = max(1_000, timeoutMs-2_000)
		} else {
			deadlineMs = timeoutMs
		}
	}
	return timeoutMs, deadlineMs, true
}

func normalizePhoneControlArgs(raw json.RawMessage) (json.RawMessage, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return json.RawMessage("{}"), nil
	}
	if trimmed[0] != '{' {
		return nil, errors.New("args must be a JSON object")
	}
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(trimmed, &obj); err != nil {
		return nil, err
	}
	return bytes.Clone(trimmed), nil
}

func (h *Hub) hubPhoneControlCall(ctx context.Context, raw json.RawMessage) (wire.PhoneControlCallResult, error) {
	var p wire.PhoneControlCallParams
	if err := decodeStrictPhoneControlJSON(raw, &p); err != nil {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: err.Error()}
	}
	deviceID := strings.TrimSpace(p.DeviceID)
	if deviceID == "" {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "device_id is required"}
	}
	op := strings.TrimSpace(p.Op)
	if p.TimeoutMs < 0 || p.TimeoutMs > phoneControlMaxTimeoutMs {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "timeout_ms out of range"}
	}
	timeoutMs, deadlineMs, validOp := phoneControlTimeouts(op, p.TimeoutMs)
	if !validOp {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "unsupported op: " + op}
	}
	args, err := normalizePhoneControlArgs(p.Args)
	if err != nil {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: err.Error()}
	}
	callID := strings.TrimSpace(p.CallID)
	if len(callID) > 128 {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "call_id too long"}
	}
	if callID == "" {
		callID = "pc_" + randomHex(8)
	}
	idempotencyKey := strings.TrimSpace(p.IdempotencyKey)
	if len(idempotencyKey) > 256 {
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "idempotency_key too long"}
	}

	h.mu.Lock()
	var target *client
	connectedDisabled := false
	for c := range h.clients {
		if clientClosed(c) {
			continue
		}
		if phoneControlDeviceID(c) != deviceID {
			continue
		}
		if c.phoneControl == nil || !c.phoneControl.Enabled {
			connectedDisabled = true
			continue
		}
		if target == nil || phoneControlNewer(c, target) {
			target = c
		}
	}
	if target == nil {
		knownOffline := h.phoneControlSeen[deviceID]
		if !knownOffline {
			for _, reg := range h.phoneTools {
				if reg.CredentialID == deviceID || reg.DeviceID == deviceID {
					knownOffline = true
					break
				}
			}
		}
		h.mu.Unlock()
		if connectedDisabled {
			return wire.PhoneControlCallResult{}, &rpc.Error{Code: "control_disabled", Message: "remote phone control is disabled on target phone"}
		}
		if knownOffline {
			return wire.PhoneControlCallResult{}, &rpc.Error{Code: "device_offline", Message: "target phone is not currently connected"}
		}
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "device_not_found", Message: "target phone control device not found"}
	}
	if h.phoneControlCalls == nil {
		h.phoneControlCalls = map[string]*phoneControlPending{}
	}
	if _, dup := h.phoneControlCalls[callID]; dup {
		h.mu.Unlock()
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "invalid_params", Message: "duplicate in-flight call_id"}
	}
	ch := make(chan phoneControlWireResult, 1)
	h.phoneControlCalls[callID] = &phoneControlPending{
		client:   target,
		deviceID: deviceID,
		ch:       ch,
	}
	h.mu.Unlock()

	defer func() {
		h.mu.Lock()
		if cur := h.phoneControlCalls[callID]; cur != nil && cur.ch == ch {
			delete(h.phoneControlCalls, callID)
		}
		h.mu.Unlock()
	}()

	target.sendJSON(phoneControlCallFrame{
		Type:           "phone_control_call",
		CallID:         callID,
		IdempotencyKey: idempotencyKey,
		Op:             op,
		DeadlineMs:     deadlineMs,
		Args:           args,
	})

	callCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	select {
	case res := <-ch:
		if res.internalErr != nil {
			return wire.PhoneControlCallResult{}, res.internalErr
		}
		return wire.PhoneControlCallResult{
			CallID:   callID,
			DeviceID: deviceID,
			Ok:       res.OK,
			Result:   res.Result,
			Error:    res.Error,
		}, nil
	case <-target.done:
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: "device_disconnected", Message: "target phone disconnected mid-call"}
	case <-callCtx.Done():
		reason := "caller_cancelled"
		code := "cancelled"
		msg := "phone control call cancelled"
		if ctx.Err() == nil && errors.Is(callCtx.Err(), context.DeadlineExceeded) {
			reason = "timeout"
			code = "timeout"
			msg = "timed out waiting for target phone"
		}
		target.sendJSON(phoneControlCancelFrame{
			Type:   "phone_control_cancel",
			CallID: callID,
			Reason: reason,
		})
		return wire.PhoneControlCallResult{}, &rpc.Error{Code: code, Message: msg}
	}
}

func (h *Hub) hubPhoneControlCancel(raw json.RawMessage) (wire.PhoneControlCancelResult, error) {
	var p wire.PhoneControlCancelParams
	if err := decodeStrictPhoneControlJSON(raw, &p); err != nil {
		return wire.PhoneControlCancelResult{}, &rpc.Error{Code: "invalid_params", Message: err.Error()}
	}
	deviceID := strings.TrimSpace(p.DeviceID)
	callID := strings.TrimSpace(p.CallID)
	if deviceID == "" || callID == "" {
		return wire.PhoneControlCancelResult{}, &rpc.Error{Code: "invalid_params", Message: "device_id and call_id are required"}
	}
	reason := strings.TrimSpace(p.Reason)
	if reason == "" {
		reason = "caller_cancelled"
	}

	h.mu.Lock()
	var pending *phoneControlPending
	if cur := h.phoneControlCalls[callID]; cur != nil && cur.deviceID == deviceID {
		pending = cur
		delete(h.phoneControlCalls, callID)
	}
	var target *client
	if pending != nil && !clientClosed(pending.client) {
		target = pending.client
	} else {
		for c := range h.clients {
			if clientClosed(c) || c.phoneControl == nil || !c.phoneControl.Enabled {
				continue
			}
			if phoneControlDeviceID(c) != deviceID {
				continue
			}
			if target == nil || phoneControlNewer(c, target) {
				target = c
			}
		}
	}
	h.mu.Unlock()

	if pending != nil {
		select {
		case pending.ch <- phoneControlWireResult{internalErr: &rpc.Error{Code: "cancelled", Message: "phone control call cancelled"}}:
		default:
		}
	}
	if target != nil {
		target.sendJSON(phoneControlCancelFrame{
			Type:   "phone_control_cancel",
			CallID: callID,
			Reason: reason,
		})
	}
	return wire.PhoneControlCancelResult{Cancelled: pending != nil || target != nil}, nil
}
