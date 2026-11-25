package hub

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/nwverse/persona/sdk/go/wire"
	"github.com/nwverse/persona/server/internal/rpc"
)

// deviceRelayChannel is one named channel registered in memory by a paired full-access device over /api/v1/ws
// (device_relay_register). It stays strictly separate from register_phone / h.phoneTools / db.Store and carries
// only domain-agnostic device metadata plus an opaque channel-specific JSON object.
type deviceRelayChannel struct {
	Channel         string
	ProtocolVersion int
	DeviceID        string
	DeviceName      string
	Platform        string
	AppVersion      string
	Meta            json.RawMessage
	ConnectedSince  time.Time
	LastSeen        time.Time
}

type deviceRelayWireResult struct {
	OK          bool
	Result      json.RawMessage
	Error       *wire.DeviceRelayError
	internalErr *rpc.Error
}

type deviceRelayPending struct {
	owner    string
	channel  string
	deviceID string
	client   *client
	ch       chan deviceRelayWireResult
}

type deviceRelayCallFrame struct {
	Type       string          `json:"type"`
	Channel    string          `json:"channel"`
	CallID     string          `json:"call_id"`
	DeadlineMs int             `json:"deadline_ms"`
	Payload    json.RawMessage `json:"payload"`
}

type deviceRelayCancelFrame struct {
	Type    string `json:"type"`
	Channel string `json:"channel"`
	CallID  string `json:"call_id"`
	Reason  string `json:"reason"`
}

const (
	deviceRelayMaxMetaBytes         = 16 << 10
	deviceRelayMaxChannelsPerClient = 16
	deviceRelayMaxPayloadBytes      = 512 << 10
	deviceRelayMaxResultBytes       = 4 << 20
	deviceRelayMaxInFlightPerDevice = 8
	deviceRelayMaxInFlightPerExt    = 16
	deviceRelayMaxInFlightGlobal    = 128
	deviceRelayDefaultTimeoutMs     = 15_000
	deviceRelayMinTimeoutMs         = 1_000
	deviceRelayMaxTimeoutMs         = 60_000
	deviceRelaySeenMaxEntries       = 1024
)

func relayErr(code, msg string) *rpc.Error { return &rpc.Error{Code: code, Message: msg} }

func deliverRelayResult(ch chan deviceRelayWireResult, res deviceRelayWireResult) {
	select {
	case ch <- res:
	default:
	}
}

// validDeviceRelayChannel matches ^[a-z0-9][a-z0-9_-]{0,31}$.
func validDeviceRelayChannel(s string) bool {
	if len(s) == 0 || len(s) > 32 {
		return false
	}
	for i := range len(s) {
		c := s[i]
		ok := (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (i > 0 && (c == '_' || c == '-'))
		if !ok {
			return false
		}
	}
	return true
}

func deviceRelayOwnerKey(from, extID string) string { return from + "\x00" + strings.TrimSpace(extID) }

func normalizeDeviceRelayObject(raw json.RawMessage, maxBytes int, defaultEmpty bool) (json.RawMessage, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		if defaultEmpty {
			return json.RawMessage("{}"), nil
		}
		return nil, nil
	}
	if len(trimmed) > maxBytes {
		return nil, errors.New("JSON object exceeds size limit")
	}
	if trimmed[0] != '{' {
		return nil, errors.New("must be a JSON object")
	}
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(trimmed, &obj); err != nil {
		return nil, err
	}
	return bytes.Clone(trimmed), nil
}

func decodeStrictDeviceRelayJSON(raw json.RawMessage, dst any) error {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return errors.New("params must be a JSON object")
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

func deviceRelayDeviceID(c *client, reg *deviceRelayChannel) string {
	if id := strings.TrimSpace(c.device); id != "" {
		return id
	}
	if reg != nil {
		return strings.TrimSpace(reg.DeviceID)
	}
	return ""
}

func clientMatchesRelayDevice(c *client, deviceID string) bool {
	if strings.TrimSpace(c.device) == deviceID {
		return true
	}
	for _, reg := range c.relayChannels {
		if reg != nil && strings.TrimSpace(reg.DeviceID) == deviceID {
			return true
		}
	}
	return false
}

func deviceRelayNewer(a *client, regA *deviceRelayChannel, b *client, regB *deviceRelayChannel) bool {
	if !a.since.Equal(b.since) {
		return a.since.After(b.since)
	}
	if regA != nil && regB != nil && !regA.LastSeen.Equal(regB.LastSeen) {
		return regA.LastSeen.After(regB.LastSeen)
	}
	return a.activeAt.After(b.activeAt)
}

func (h *Hub) onDeviceRelayRegister(c *client, data []byte) {
	if c.role != roleFull {
		return
	}
	var msg struct {
		Channel         string          `json:"channel"`
		ProtocolVersion int             `json:"protocol_version"`
		DeviceID        string          `json:"device_id"`
		DeviceName      string          `json:"device_name"`
		Platform        string          `json:"platform"`
		AppVersion      string          `json:"app_version"`
		Meta            json.RawMessage `json:"meta"`
	}
	if json.Unmarshal(data, &msg) != nil || !validDeviceRelayChannel(msg.Channel) {
		return
	}
	meta, err := normalizeDeviceRelayObject(msg.Meta, deviceRelayMaxMetaBytes, false)
	if err != nil {
		return
	}
	c.mu.Lock()
	helloPlatform, helloVersion := strings.TrimSpace(c.meta.Platform), strings.TrimSpace(c.meta.Version)
	c.mu.Unlock()

	now := time.Now().UTC()
	h.mu.Lock()
	defer h.mu.Unlock()
	deviceID := cmp.Or(strings.TrimSpace(c.device), strings.TrimSpace(msg.DeviceID))
	if deviceID == "" {
		return
	}
	if c.relayChannels == nil {
		c.relayChannels = map[string]*deviceRelayChannel{}
	}
	prev := c.relayChannels[msg.Channel]
	if prev == nil && len(c.relayChannels) >= deviceRelayMaxChannelsPerClient {
		return
	}
	connectedSince := now
	if prev != nil && !prev.ConnectedSince.IsZero() {
		connectedSince = prev.ConnectedSince
	} else if !c.since.IsZero() {
		connectedSince = c.since.UTC()
	}
	c.relayChannels[msg.Channel] = &deviceRelayChannel{
		Channel:         msg.Channel,
		ProtocolVersion: max(1, msg.ProtocolVersion),
		DeviceID:        deviceID,
		DeviceName:      cmp.Or(strings.TrimSpace(msg.DeviceName), strings.TrimSpace(c.name), deviceID),
		Platform:        cmp.Or(strings.TrimSpace(msg.Platform), helloPlatform),
		AppVersion:      cmp.Or(strings.TrimSpace(msg.AppVersion), helloVersion),
		Meta:            meta,
		ConnectedSince:  connectedSince,
		LastSeen:        now,
	}
	if h.deviceRelaySeen == nil {
		h.deviceRelaySeen = map[string]bool{}
	}
	if len(h.deviceRelaySeen) >= deviceRelaySeenMaxEntries && !h.deviceRelaySeen[deviceID] {
		clear(h.deviceRelaySeen)
	}
	h.deviceRelaySeen[deviceID] = true
}

func (h *Hub) onDeviceRelayUnregister(c *client, data []byte) {
	if c.role != roleFull {
		return
	}
	var msg struct {
		Channel string `json:"channel"`
	}
	if json.Unmarshal(data, &msg) != nil || !validDeviceRelayChannel(msg.Channel) {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(c.relayChannels, msg.Channel)
	h.failClientDeviceRelayCallsLocked(c, msg.Channel, "channel_unavailable", "relay channel unregistered on target device")
}

func (h *Hub) onDeviceRelayResult(c *client, data []byte) {
	if c.role != roleFull {
		return
	}
	var msg struct {
		Channel string                 `json:"channel"`
		CallID  string                 `json:"call_id"`
		OK      bool                   `json:"ok"`
		Result  json.RawMessage        `json:"result"`
		Error   *wire.DeviceRelayError `json:"error"`
	}
	if json.Unmarshal(data, &msg) != nil || strings.TrimSpace(msg.CallID) == "" || !validDeviceRelayChannel(msg.Channel) {
		return
	}
	h.mu.Lock()
	pending := h.deviceRelayCalls[msg.CallID]
	if pending == nil || pending.client != c || pending.channel != msg.Channel {
		h.mu.Unlock()
		return
	}
	h.removeDeviceRelayCallLocked(msg.CallID)
	if reg := c.relayChannels[msg.Channel]; reg != nil {
		reg.LastSeen = time.Now().UTC()
	}
	h.mu.Unlock()

	trimmed := bytes.TrimSpace(msg.Result)
	if len(trimmed) > deviceRelayMaxResultBytes {
		deliverRelayResult(pending.ch, deviceRelayWireResult{internalErr: relayErr("response_too_large", "device relay result exceeds 4 MiB limit")})
		return
	}
	var result json.RawMessage
	if len(trimmed) > 0 && !bytes.Equal(trimmed, []byte("null")) {
		result = bytes.Clone(trimmed)
	}
	deliverRelayResult(pending.ch, deviceRelayWireResult{OK: msg.OK, Result: result, Error: msg.Error})
}

// removeDeviceRelayCallLocked removes callID and updates all concurrency counters. Caller holds h.mu.
func (h *Hub) removeDeviceRelayCallLocked(callID string) *deviceRelayPending {
	pending := h.deviceRelayCalls[callID]
	if pending == nil {
		return nil
	}
	delete(h.deviceRelayCalls, callID)
	if n := h.deviceRelayByExt[pending.owner] - 1; n <= 0 {
		delete(h.deviceRelayByExt, pending.owner)
	} else {
		h.deviceRelayByExt[pending.owner] = n
	}
	if n := h.deviceRelayByDevice[pending.deviceID] - 1; n <= 0 {
		delete(h.deviceRelayByDevice, pending.deviceID)
	} else {
		h.deviceRelayByDevice[pending.deviceID] = n
	}
	return pending
}

// failClientDeviceRelayCallsLocked fails in-flight relay calls bound to c (and channel when non-empty). Caller holds h.mu.
func (h *Hub) failClientDeviceRelayCallsLocked(c *client, channel, code, message string) {
	for id, pending := range h.deviceRelayCalls {
		if pending.client != c || (channel != "" && pending.channel != channel) {
			continue
		}
		h.removeDeviceRelayCallLocked(id)
		deliverRelayResult(pending.ch, deviceRelayWireResult{internalErr: relayErr(code, message)})
	}
}

func (h *Hub) hubDeviceRelayDevices(raw json.RawMessage) (wire.DeviceRelayDevicesResult, error) {
	var p wire.DeviceRelayDevicesParams
	if err := decodeStrictDeviceRelayJSON(raw, &p); err != nil {
		return wire.DeviceRelayDevicesResult{}, relayErr("invalid_params", err.Error())
	}
	if !validDeviceRelayChannel(p.Channel) {
		return wire.DeviceRelayDevicesResult{}, relayErr("invalid_params", "invalid channel")
	}
	type candidate struct {
		client *client
		reg    deviceRelayChannel
		sim    bool
		device wire.DeviceRelayDevice
	}
	h.mu.Lock()
	primaryCred := h.primaryPhone.CredentialID
	bestByID := map[string]*candidate{}
	for c := range h.clients {
		if clientClosed(c) || c.role != roleFull {
			continue
		}
		reg := c.relayChannels[p.Channel]
		devID := deviceRelayDeviceID(c, reg)
		if reg == nil || devID == "" {
			continue
		}
		if prev, ok := bestByID[devID]; ok && !deviceRelayNewer(c, reg, prev.client, &prev.reg) {
			continue
		}
		regCopy, credID := *reg, strings.TrimSpace(c.device)
		bestByID[devID] = &candidate{
			client: c, reg: regCopy, sim: c.sim,
			device: wire.DeviceRelayDevice{
				DeviceID: devID, CredentialID: credID, Name: regCopy.DeviceName,
				Platform: regCopy.Platform, AppVersion: regCopy.AppVersion,
				Online: true, Primary: primaryCred != "" && credID == primaryCred,
				Channel: regCopy.Channel, ProtocolVersion: regCopy.ProtocolVersion,
				Meta: bytes.Clone(regCopy.Meta), ConnectedSince: regCopy.ConnectedSince.UTC(), LastSeen: regCopy.LastSeen.UTC(),
			},
		}
	}
	h.mu.Unlock()

	list := make([]*candidate, 0, len(bestByID))
	for _, cand := range bestByID {
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
		if !a.device.LastSeen.Equal(b.device.LastSeen) {
			return a.device.LastSeen.After(b.device.LastSeen)
		}
		return a.device.DeviceID < b.device.DeviceID
	})
	devices := make([]wire.DeviceRelayDevice, len(list))
	for i, cand := range list {
		devices[i] = cand.device
	}
	return wire.DeviceRelayDevicesResult{Devices: devices}, nil
}

func (h *Hub) hubDeviceRelayCall(ctx context.Context, from, extID string, raw json.RawMessage) (wire.DeviceRelayCallResult, error) {
	var p wire.DeviceRelayCallParams
	if err := decodeStrictDeviceRelayJSON(raw, &p); err != nil {
		return wire.DeviceRelayCallResult{}, relayErr("invalid_params", err.Error())
	}
	deviceID := strings.TrimSpace(p.DeviceID)
	if deviceID == "" || !validDeviceRelayChannel(p.Channel) {
		return wire.DeviceRelayCallResult{}, relayErr("invalid_params", "device_id and valid channel are required")
	}
	timeoutMs := deviceRelayDefaultTimeoutMs
	if p.TimeoutMs != 0 {
		if p.TimeoutMs < deviceRelayMinTimeoutMs || p.TimeoutMs > deviceRelayMaxTimeoutMs {
			return wire.DeviceRelayCallResult{}, relayErr("invalid_params", "timeout_ms must be 0 or in [1000, 60000]")
		}
		timeoutMs = p.TimeoutMs
	}
	deadlineMs := timeoutMs
	if timeoutMs > 2_000 {
		deadlineMs = max(1_000, timeoutMs-2_000)
	}
	payload, err := normalizeDeviceRelayObject(p.Payload, deviceRelayMaxPayloadBytes, true)
	if err != nil {
		return wire.DeviceRelayCallResult{}, relayErr("invalid_params", "payload: "+err.Error())
	}
	callID := strings.TrimSpace(p.CallID)
	if len(callID) > 128 {
		return wire.DeviceRelayCallResult{}, relayErr("invalid_params", "call_id too long")
	}
	if callID == "" {
		callID = "dr_" + randomHex(8)
	}
	owner := deviceRelayOwnerKey(from, extID)

	h.mu.Lock()
	var target *client
	var targetReg *deviceRelayChannel
	connectedNoChannel := false
	for c := range h.clients {
		if clientClosed(c) || c.role != roleFull || !clientMatchesRelayDevice(c, deviceID) {
			continue
		}
		reg := c.relayChannels[p.Channel]
		if reg == nil {
			connectedNoChannel = true
			continue
		}
		if target == nil || deviceRelayNewer(c, reg, target, targetReg) {
			target, targetReg = c, reg
		}
	}
	if target == nil {
		knownOffline := h.deviceRelaySeen[deviceID]
		for _, reg := range h.phoneTools {
			if knownOffline || reg.CredentialID == deviceID || reg.DeviceID == deviceID {
				knownOffline = true
				break
			}
		}
		h.mu.Unlock()
		if connectedNoChannel {
			return wire.DeviceRelayCallResult{}, relayErr("channel_unavailable", "target device is connected but channel is not registered")
		}
		if knownOffline {
			return wire.DeviceRelayCallResult{}, relayErr("device_offline", "target device is not currently connected")
		}
		return wire.DeviceRelayCallResult{}, relayErr("device_not_found", "target device not found")
	}
	if h.deviceRelayCalls == nil {
		h.deviceRelayCalls = map[string]*deviceRelayPending{}
	}
	if h.deviceRelayByExt == nil {
		h.deviceRelayByExt = map[string]int{}
	}
	if h.deviceRelayByDevice == nil {
		h.deviceRelayByDevice = map[string]int{}
	}
	if _, dup := h.deviceRelayCalls[callID]; dup {
		h.mu.Unlock()
		return wire.DeviceRelayCallResult{}, relayErr("invalid_params", "duplicate in-flight call_id")
	}
	if len(h.deviceRelayCalls) >= deviceRelayMaxInFlightGlobal ||
		h.deviceRelayByExt[owner] >= deviceRelayMaxInFlightPerExt ||
		h.deviceRelayByDevice[deviceID] >= deviceRelayMaxInFlightPerDevice {
		h.mu.Unlock()
		return wire.DeviceRelayCallResult{}, relayErr("busy", "device relay concurrency limit reached")
	}
	ch := make(chan deviceRelayWireResult, 1)
	h.deviceRelayCalls[callID] = &deviceRelayPending{owner: owner, channel: p.Channel, deviceID: deviceID, client: target, ch: ch}
	h.deviceRelayByExt[owner]++
	h.deviceRelayByDevice[deviceID]++
	h.mu.Unlock()

	defer func() {
		h.mu.Lock()
		if cur := h.deviceRelayCalls[callID]; cur != nil && cur.ch == ch {
			h.removeDeviceRelayCallLocked(callID)
		}
		h.mu.Unlock()
	}()

	target.sendJSON(deviceRelayCallFrame{Type: "device_relay_call", Channel: p.Channel, CallID: callID, DeadlineMs: deadlineMs, Payload: payload})

	callCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	select {
	case res := <-ch:
		if res.internalErr != nil {
			return wire.DeviceRelayCallResult{}, res.internalErr
		}
		return wire.DeviceRelayCallResult{CallID: callID, DeviceID: deviceID, Channel: p.Channel, Ok: res.OK, Result: res.Result, Error: res.Error}, nil
	case <-target.done:
		return wire.DeviceRelayCallResult{}, relayErr("device_disconnected", "target device disconnected mid-call")
	case <-callCtx.Done():
		reason, code, msg := "caller_cancelled", "cancelled", "device relay call cancelled"
		if ctx.Err() == nil && errors.Is(callCtx.Err(), context.DeadlineExceeded) {
			reason, code, msg = "timeout", "timeout", "timed out waiting for target device"
		}
		target.sendJSON(deviceRelayCancelFrame{Type: "device_relay_cancel", Channel: p.Channel, CallID: callID, Reason: reason})
		return wire.DeviceRelayCallResult{}, relayErr(code, msg)
	}
}

func (h *Hub) hubDeviceRelayCancel(from, extID string, raw json.RawMessage) (wire.DeviceRelayCancelResult, error) {
	var p wire.DeviceRelayCancelParams
	if err := decodeStrictDeviceRelayJSON(raw, &p); err != nil {
		return wire.DeviceRelayCancelResult{}, relayErr("invalid_params", err.Error())
	}
	deviceID, callID := strings.TrimSpace(p.DeviceID), strings.TrimSpace(p.CallID)
	if deviceID == "" || callID == "" || len(callID) > 128 || !validDeviceRelayChannel(p.Channel) {
		return wire.DeviceRelayCancelResult{}, relayErr("invalid_params", "valid device_id, channel, and call_id are required")
	}
	reason := cmp.Or(strings.TrimSpace(p.Reason), "caller_cancelled")
	if len(reason) > 128 {
		return wire.DeviceRelayCancelResult{}, relayErr("invalid_params", "reason too long")
	}
	owner := deviceRelayOwnerKey(from, extID)

	h.mu.Lock()
	cur := h.deviceRelayCalls[callID]
	if cur == nil {
		h.mu.Unlock()
		return wire.DeviceRelayCancelResult{Cancelled: false}, nil
	}
	if cur.owner != owner {
		h.mu.Unlock()
		return wire.DeviceRelayCancelResult{Cancelled: false}, relayErr("permission_denied", "cannot cancel another extension's relay call")
	}
	if cur.deviceID != deviceID || cur.channel != p.Channel {
		h.mu.Unlock()
		return wire.DeviceRelayCancelResult{Cancelled: false}, nil
	}
	pending := h.removeDeviceRelayCallLocked(callID)
	var target *client
	if pending != nil && !clientClosed(pending.client) {
		target = pending.client
	}
	h.mu.Unlock()

	if pending != nil {
		deliverRelayResult(pending.ch, deviceRelayWireResult{internalErr: relayErr("cancelled", "device relay call cancelled")})
	}
	if target != nil {
		target.sendJSON(deviceRelayCancelFrame{Type: "device_relay_cancel", Channel: p.Channel, CallID: callID, Reason: reason})
	}
	return wire.DeviceRelayCancelResult{Cancelled: pending != nil}, nil
}
