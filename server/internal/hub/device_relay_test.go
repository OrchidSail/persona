package hub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nwverse/persona/sdk/go/wire"
	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/db"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/rpc"
)

func addTestRelayClient(t *testing.T, h *Hub, deviceID, role string, sim bool, since time.Time) (*client, chan struct{}) {
	t.Helper()
	done := make(chan struct{})
	c := &client{
		hub:      h,
		out:      make(chan []byte, 64),
		done:     done,
		device:   deviceID,
		role:     role,
		sim:      sim,
		since:    since,
		activeAt: since,
	}
	h.mu.Lock()
	if h.clients == nil {
		h.clients = map[*client]struct{}{}
	}
	h.clients[c] = struct{}{}
	h.mu.Unlock()
	t.Cleanup(func() {
		h.mu.Lock()
		delete(h.clients, c)
		h.mu.Unlock()
	})
	return c, done
}

func relayRegisterFrame(channel, deviceID, name, platform, appVersion string, meta any) []byte {
	m := map[string]any{
		"type":             "device_relay_register",
		"channel":          channel,
		"protocol_version": 1,
		"device_id":        deviceID,
		"device_name":      name,
	}
	if platform != "" {
		m["platform"] = platform
	}
	if appVersion != "" {
		m["app_version"] = appVersion
	}
	if meta != nil {
		m["meta"] = meta
	}
	b, _ := json.Marshal(m)
	return b
}

func TestDeviceRelayRegistrationIsolationLimitsAndDiscovery(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	// Answer-only and scoped clients cannot register relay channels.
	watch, _ := addTestRelayClient(t, h, "dev_watch", roleAnswer, false, now)
	scoped, _ := addTestRelayClient(t, h, "dev_scoped", roleScoped, false, now)
	h.onClientMessage(context.Background(), watch, relayRegisterFrame("alpha", "dev_watch", "Watch", "watchos", "1.0", map[string]any{"k": 1}))
	h.onClientMessage(context.Background(), scoped, relayRegisterFrame("alpha", "dev_scoped", "Scoped", "web", "1.0", map[string]any{"k": 1}))

	// Simulator, secondary real device (with client_hello fallback), and two overlapping connections for primary.
	simClient, _ := addTestRelayClient(t, h, "dev_sim", roleFull, true, now.Add(4*time.Second))
	secondReal, _ := addTestRelayClient(t, h, "dev_second", roleFull, false, now.Add(2*time.Second))
	secondReal.mu.Lock()
	secondReal.meta.Platform = "ios"
	secondReal.meta.Version = "2.1.0"
	secondReal.mu.Unlock()
	oldPrimary, _ := addTestRelayClient(t, h, "dev_primary", roleFull, false, now.Add(1*time.Second))
	newPrimary, _ := addTestRelayClient(t, h, "dev_primary", roleFull, false, now.Add(3*time.Second))

	h.mu.Lock()
	h.primaryPhone = phonePrimary{CredentialID: "dev_primary", Name: "Primary"}
	h.mu.Unlock()

	h.onClientMessage(context.Background(), simClient, relayRegisterFrame("alpha", "dev_sim", "Simulator", "android", "1.0", map[string]any{"sim": true}))
	h.onClientMessage(context.Background(), secondReal, relayRegisterFrame("alpha", "dev_second", "Second", "", "", map[string]any{"tier": "b"}))
	h.onClientMessage(context.Background(), secondReal, relayRegisterFrame("beta", "dev_second", "Second", "ios", "2.1.0", map[string]any{"secret": "beta_only"}))
	h.onClientMessage(context.Background(), oldPrimary, relayRegisterFrame("alpha", "dev_primary", "Old Conn", "android", "1.0", map[string]any{"rev": 1}))
	h.onClientMessage(context.Background(), newPrimary, relayRegisterFrame("alpha", "dev_primary", "Primary New", "android", "1.4", map[string]any{"rev": 2}))

	// Invalid meta (non-object or > 16 KiB) and invalid channel names are ignored.
	h.onClientMessage(context.Background(), secondReal, relayRegisterFrame("gamma", "dev_second", "Second", "ios", "2.1.0", []int{1, 2, 3}))
	oversizedMeta := map[string]any{"blob": strings.Repeat("x", deviceRelayMaxMetaBytes)}
	h.onClientMessage(context.Background(), secondReal, relayRegisterFrame("gamma", "dev_second", "Second", "ios", "2.1.0", oversizedMeta))
	h.onClientMessage(context.Background(), secondReal, relayRegisterFrame("BadUpper", "dev_second", "Second", "ios", "2.1.0", map[string]any{}))

	// Channel cap per client: 16 channels allowed, 17th ignored, updating existing channel still succeeds.
	capClient, _ := addTestRelayClient(t, h, "dev_cap", roleFull, false, now.Add(5*time.Second))
	for i := range deviceRelayMaxChannelsPerClient {
		ch := fmt.Sprintf("ch_%02d", i)
		h.onClientMessage(context.Background(), capClient, relayRegisterFrame(ch, "dev_cap", "Cap", "linux", "1.0", map[string]any{"i": i}))
	}
	h.onClientMessage(context.Background(), capClient, relayRegisterFrame("ch_overflow", "dev_cap", "Cap", "linux", "1.0", map[string]any{"i": 99}))
	h.onClientMessage(context.Background(), capClient, relayRegisterFrame("ch_00", "dev_cap", "Cap Updated", "linux", "1.1", map[string]any{"updated": true}))
	h.mu.Lock()
	if len(capClient.relayChannels) != deviceRelayMaxChannelsPerClient {
		h.mu.Unlock()
		t.Fatalf("capClient channels = %d, want %d", len(capClient.relayChannels), deviceRelayMaxChannelsPerClient)
	}
	if capClient.relayChannels["ch_overflow"] != nil || capClient.relayChannels["ch_00"].DeviceName != "Cap Updated" {
		h.mu.Unlock()
		t.Fatalf("unexpected channel cap state: %+v", capClient.relayChannels)
	}
	h.mu.Unlock()

	// Structural isolation: relay registrations never contaminate h.phoneTools, phoneToolList, or phonePrimaryView.
	if got := h.phoneToolList(); len(got) != 0 {
		t.Fatalf("device relay polluted phoneToolList: %+v", got)
	}
	h.mu.Lock()
	if len(h.phoneTools) != 0 {
		h.mu.Unlock()
		t.Fatalf("device relay polluted h.phoneTools: %+v", h.phoneTools)
	}
	h.mu.Unlock()
	if view := h.phonePrimaryView([]db.DeviceToken{{ID: "dev_primary"}}); len(view.Candidates) != 0 {
		t.Fatalf("device relay polluted phonePrimaryView: %+v", view.Candidates)
	}

	// Discover channel alpha via extAppCall: deduplicates dev_primary, preserves client_hello fallback, sorts primary -> real -> sim.
	raw, handled, err := h.extAppCall(context.Background(), "mgr-1", assistantwire.AppCall{
		AppID:  extwire.AppHub,
		Method: extwire.HubDeviceRelayDevices,
		Params: json.RawMessage(`{"extension":"ext-a","request":{"channel":"alpha"}}`),
	})
	if !handled || err != nil {
		t.Fatalf("devices(alpha): handled=%v err=%v", handled, err)
	}
	var res wire.DeviceRelayDevicesResult
	if err := json.Unmarshal(raw, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Devices) != 3 {
		t.Fatalf("got %d alpha devices, want 3: %+v", len(res.Devices), res.Devices)
	}
	if res.Devices[0].DeviceID != "dev_primary" || res.Devices[0].Name != "Primary New" || !res.Devices[0].Primary || string(res.Devices[0].Meta) != `{"rev":2}` {
		t.Fatalf("devices[0] = %+v, want newest primary", res.Devices[0])
	}
	if res.Devices[1].DeviceID != "dev_second" || res.Devices[1].Platform != "ios" || res.Devices[1].AppVersion != "2.1.0" {
		t.Fatalf("devices[1] = %+v, want secondReal with hello fallback", res.Devices[1])
	}
	if res.Devices[2].DeviceID != "dev_sim" {
		t.Fatalf("devices[2] = %+v, want simulator last", res.Devices[2])
	}

	// Channel isolation: beta lists only dev_second with beta meta; gamma rejected meta so empty.
	betaRes, err := h.hubDeviceRelayDevices(json.RawMessage(`{"channel":"beta"}`))
	if err != nil || len(betaRes.Devices) != 1 || betaRes.Devices[0].DeviceID != "dev_second" || string(betaRes.Devices[0].Meta) != `{"secret":"beta_only"}` {
		t.Fatalf("devices(beta) = %+v, err=%v", betaRes, err)
	}
	gammaRes, err := h.hubDeviceRelayDevices(json.RawMessage(`{"channel":"gamma"}`))
	if err != nil || len(gammaRes.Devices) != 0 {
		t.Fatalf("devices(gamma) = %+v, err=%v, want empty", gammaRes, err)
	}
}

func TestDeviceRelayTargetedRoutingSpoofAndPayloadLimits(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	devA, _ := addTestRelayClient(t, h, "dev_a", roleFull, false, now)
	devB, _ := addTestRelayClient(t, h, "dev_b", roleFull, false, now.Add(time.Second))
	devNoChan, _ := addTestRelayClient(t, h, "dev_nochan", roleFull, false, now.Add(2*time.Second))

	h.mu.Lock()
	h.primaryPhone = phonePrimary{CredentialID: "dev_a", Name: "Dev A"}
	h.mu.Unlock()

	h.onClientMessage(context.Background(), devA, relayRegisterFrame("alpha", "dev_a", "Dev A", "android", "1.0", map[string]any{}))
	h.onClientMessage(context.Background(), devB, relayRegisterFrame("alpha", "dev_b", "Dev B", "android", "1.0", map[string]any{}))
	_ = devNoChan

	var re *rpc.Error
	// Unknown device_id returns device_not_found without falling back to primary dev_a.
	_, err := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_missing","channel":"alpha"}`))
	if !errors.As(err, &re) || re.Code != "device_not_found" {
		t.Fatalf("missing device: got %v, want device_not_found", err)
	}

	// Connected device without the channel returns channel_unavailable.
	_, err = h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_nochan","channel":"alpha"}`))
	if !errors.As(err, &re) || re.Code != "channel_unavailable" {
		t.Fatalf("no channel device: got %v, want channel_unavailable", err)
	}

	// Strict params, channel syntax, timeout bounds [1s, 60s], and payload <= 512 KiB.
	bigPayload := fmt.Sprintf(`{"device_id":"dev_b","channel":"alpha","payload":{"blob":"%s"}}`, strings.Repeat("a", deviceRelayMaxPayloadBytes))
	for _, bad := range []string{
		`{}`,
		`{"device_id":"dev_b","channel":"Bad"}`,
		`{"device_id":"dev_b","channel":"alpha","extra":true}`,
		`{"device_id":"dev_b","channel":"alpha","payload":[1,2,3]}`,
		`{"device_id":"","channel":"alpha"}`,
		`{"device_id":"dev_b","channel":"alpha","timeout_ms":999}`,
		`{"device_id":"dev_b","channel":"alpha","timeout_ms":60001}`,
		`{"device_id":"dev_b","channel":"alpha","timeout_ms":-1}`,
		`{"device_id":"dev_b","channel":"alpha","call_id":"` + strings.Repeat("c", 129) + `"}`,
		bigPayload,
	} {
		_, err = h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(bad))
		if !errors.As(err, &re) || re.Code != "invalid_params" {
			t.Fatalf("bad call len=%d: got %v, want invalid_params", len(bad), err)
		}
	}

	// Targeted dispatch goes ONLY to devB, with derived deadline_ms, and ignores spoofs from wrong device or wrong channel.
	done := make(chan wire.DeviceRelayCallResult, 1)
	errCh := make(chan error, 1)
	go func() {
		r, e := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_b","channel":"alpha","call_id":"dr_b1","timeout_ms":10000,"payload":{"step":1}}`))
		if e != nil {
			errCh <- e
			return
		}
		done <- r
	}()

	var dispatched deviceRelayCallFrame
	select {
	case raw := <-devB.out:
		if err := json.Unmarshal(raw, &dispatched); err != nil {
			t.Fatal(err)
		}
	case raw := <-devA.out:
		t.Fatalf("call routed to wrong client devA: %s", raw)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for dispatch on devB")
	}
	if dispatched.Type != "device_relay_call" || dispatched.Channel != "alpha" || dispatched.CallID != "dr_b1" || dispatched.DeadlineMs != 8000 || string(dispatched.Payload) != `{"step":1}` {
		t.Fatalf("unexpected dispatched frame: %+v", dispatched)
	}

	// Duplicate in-flight call_id rejected with invalid_params.
	_, err = h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_b","channel":"alpha","call_id":"dr_b1"}`))
	if !errors.As(err, &re) || re.Code != "invalid_params" {
		t.Fatalf("duplicate call_id: got %v, want invalid_params", err)
	}

	// Spoofed result from devA and wrong-channel result from devB are both ignored.
	spoofDev, _ := json.Marshal(map[string]any{"type": "device_relay_result", "channel": "alpha", "call_id": "dr_b1", "ok": true, "result": map[string]any{"spoof": 1}})
	spoofCh, _ := json.Marshal(map[string]any{"type": "device_relay_result", "channel": "beta", "call_id": "dr_b1", "ok": true, "result": map[string]any{"spoof": 2}})
	h.onClientMessage(context.Background(), devA, spoofDev)
	h.onClientMessage(context.Background(), devB, spoofCh)
	select {
	case r := <-done:
		t.Fatalf("spoofed result accepted: %+v", r)
	case <-time.After(50 * time.Millisecond):
	}

	// Genuine result from devB on alpha succeeds.
	realResp, _ := json.Marshal(map[string]any{"type": "device_relay_result", "channel": "alpha", "call_id": "dr_b1", "ok": true, "result": map[string]any{"value": 42}})
	h.onClientMessage(context.Background(), devB, realResp)
	select {
	case err := <-errCh:
		t.Fatalf("unexpected error: %v", err)
	case res := <-done:
		if !res.Ok || res.CallID != "dr_b1" || res.DeviceID != "dev_b" || res.Channel != "alpha" || string(res.Result) != `{"value":42}` {
			t.Fatalf("unexpected result: %+v", res)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for genuine result")
	}

	// Oversized result (> 4 MiB) settles call with response_too_large.
	go func() {
		_, e := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_b","channel":"alpha","call_id":"dr_big","timeout_ms":5000}`))
		errCh <- e
	}()
	select {
	case <-devB.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch for oversized test")
	}
	bigResp, _ := json.Marshal(map[string]any{
		"type":    "device_relay_result",
		"channel": "alpha",
		"call_id": "dr_big",
		"ok":      true,
		"result":  map[string]any{"data": strings.Repeat("z", deviceRelayMaxResultBytes)},
	})
	h.onClientMessage(context.Background(), devB, bigResp)
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "response_too_large" {
			t.Fatalf("oversized result err = %v, want response_too_large", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for response_too_large")
	}
}

func TestDeviceRelayConcurrencyGates(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	// Create 18 devices so we can exercise per-device (8), per-extension (16), and global (128) limits cleanly.
	clients := make([]*client, 18)
	for i := range clients {
		devID := fmt.Sprintf("dev_%02d", i)
		c, _ := addTestRelayClient(t, h, devID, roleFull, false, now.Add(time.Duration(i)*time.Millisecond))
		h.onClientMessage(context.Background(), c, relayRegisterFrame("alpha", devID, devID, "android", "1.0", nil))
		clients[i] = c
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var wg sync.WaitGroup
	startCall := func(extID, devID, callID string, target *client) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = h.hubDeviceRelayCall(ctx, "mgr", extID, json.RawMessage(
				fmt.Sprintf(`{"device_id":%q,"channel":"alpha","call_id":%q,"timeout_ms":20000}`, devID, callID),
			))
		}()
		select {
		case <-target.out:
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for call %s on %s", callID, devID)
		}
	}

	// 1. Single-device limit = 8: 9th call on dev_00 returns busy.
	for i := range deviceRelayMaxInFlightPerDevice {
		startCall("ext_dev", "dev_00", fmt.Sprintf("d0_%d", i), clients[0])
	}
	var re *rpc.Error
	_, err := h.hubDeviceRelayCall(ctx, "mgr", "ext_other", json.RawMessage(`{"device_id":"dev_00","channel":"alpha","call_id":"d0_over"}`))
	if !errors.As(err, &re) || re.Code != "busy" {
		t.Fatalf("per-device overflow: got %v, want busy", err)
	}

	// 2. Single-extension limit = 16: ext_dev already has 8 on dev_00; add 8 on dev_01 -> 17th on dev_02 returns busy,
	// while another extension can still call dev_02.
	for i := range deviceRelayMaxInFlightPerDevice {
		startCall("ext_dev", "dev_01", fmt.Sprintf("d1_%d", i), clients[1])
	}
	_, err = h.hubDeviceRelayCall(ctx, "mgr", "ext_dev", json.RawMessage(`{"device_id":"dev_02","channel":"alpha","call_id":"ext_over"}`))
	if !errors.As(err, &re) || re.Code != "busy" {
		t.Fatalf("per-extension overflow: got %v, want busy", err)
	}

	// 3. Global limit = 128: fill remaining 112 slots across devices 2..15 (14 devices x 8 each) using distinct extensions.
	for d := 2; d < 16; d++ {
		extID := fmt.Sprintf("ext_pool_%02d", d/2)
		devID := fmt.Sprintf("dev_%02d", d)
		for j := range deviceRelayMaxInFlightPerDevice {
			startCall(extID, devID, fmt.Sprintf("g_%d_%d", d, j), clients[d])
		}
	}
	_, err = h.hubDeviceRelayCall(ctx, "mgr", "ext_fresh", json.RawMessage(`{"device_id":"dev_16","channel":"alpha","call_id":"global_over"}`))
	if !errors.As(err, &re) || re.Code != "busy" {
		t.Fatalf("global overflow: got %v, want busy", err)
	}

	// Cancel all and confirm every concurrency map drains cleanly back to zero.
	cancel()
	wg.Wait()
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.deviceRelayCalls) != 0 || len(h.deviceRelayByExt) != 0 || len(h.deviceRelayByDevice) != 0 {
		t.Fatalf("leaked concurrency tracking: calls=%d ext=%v dev=%v", len(h.deviceRelayCalls), h.deviceRelayByExt, h.deviceRelayByDevice)
	}
}

func TestDeviceRelayFastFailDisconnectUnregisterTimeoutAndCrossExtCancel(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()
	var re *rpc.Error

	// 1. Mid-call disconnect fails immediately with device_disconnected, then subsequent call returns device_offline.
	devDrop, doneDrop := addTestRelayClient(t, h, "dev_drop", roleFull, false, now)
	h.onClientMessage(context.Background(), devDrop, relayRegisterFrame("alpha", "dev_drop", "Drop", "android", "1.0", nil))

	errCh := make(chan error, 2)
	go func() {
		_, err := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_drop","channel":"alpha","call_id":"dr_drop","timeout_ms":20000}`))
		errCh <- err
	}()
	select {
	case <-devDrop.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch on devDrop")
	}
	start := time.Now()
	close(doneDrop)
	h.dropClient(devDrop)
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "device_disconnected" {
			t.Fatalf("disconnect err = %v, want device_disconnected", err)
		}
		if time.Since(start) > 2*time.Second {
			t.Fatalf("disconnect took too long: %v", time.Since(start))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("call hung after disconnect")
	}
	_, err := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_drop","channel":"alpha"}`))
	if !errors.As(err, &re) || re.Code != "device_offline" {
		t.Fatalf("after disconnect: got %v, want device_offline", err)
	}

	// 2. Unregistering channel alpha fails alpha's call with channel_unavailable while channel beta on the same client keeps running.
	devMulti, _ := addTestRelayClient(t, h, "dev_multi", roleFull, false, now.Add(time.Second))
	h.onClientMessage(context.Background(), devMulti, relayRegisterFrame("alpha", "dev_multi", "Multi", "android", "1.0", nil))
	h.onClientMessage(context.Background(), devMulti, relayRegisterFrame("beta", "dev_multi", "Multi", "android", "1.0", nil))

	alphaErrCh := make(chan error, 1)
	betaResCh := make(chan wire.DeviceRelayCallResult, 1)
	go func() {
		_, e := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-a", json.RawMessage(`{"device_id":"dev_multi","channel":"alpha","call_id":"dr_m_alpha","timeout_ms":15000}`))
		alphaErrCh <- e
	}()
	go func() {
		r, e := h.hubDeviceRelayCall(context.Background(), "mgr", "ext-b", json.RawMessage(`{"device_id":"dev_multi","channel":"beta","call_id":"dr_m_beta","timeout_ms":15000}`))
		if e != nil {
			t.Errorf("beta call failed unexpectedly: %v", e)
			return
		}
		betaResCh <- r
	}()
	for range 2 {
		select {
		case <-devMulti.out:
		case <-time.After(2 * time.Second):
			t.Fatal("missing dispatch on devMulti")
		}
	}
	unreg, _ := json.Marshal(map[string]any{"type": "device_relay_unregister", "channel": "alpha"})
	h.onClientMessage(context.Background(), devMulti, unreg)
	select {
	case err := <-alphaErrCh:
		if !errors.As(err, &re) || re.Code != "channel_unavailable" {
			t.Fatalf("unregister alpha err = %v, want channel_unavailable", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("alpha call hung after unregister")
	}
	betaResp, _ := json.Marshal(map[string]any{"type": "device_relay_result", "channel": "beta", "call_id": "dr_m_beta", "ok": true, "result": map[string]any{"beta": true}})
	h.onClientMessage(context.Background(), devMulti, betaResp)
	select {
	case res := <-betaResCh:
		if !res.Ok || res.Channel != "beta" {
			t.Fatalf("unexpected beta result: %+v", res)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("beta call failed to complete after alpha unregister")
	}

	// 3. Cross-extension cancel is rejected with permission_denied; own extension cancel succeeds and forwards device_relay_cancel.
	devCancel, _ := addTestRelayClient(t, h, "dev_cancel", roleFull, false, now.Add(2*time.Second))
	h.onClientMessage(context.Background(), devCancel, relayRegisterFrame("alpha", "dev_cancel", "Cancel", "android", "1.0", nil))

	go func() {
		_, e := h.hubDeviceRelayCall(context.Background(), "mgr-1", "ext-owner", json.RawMessage(`{"device_id":"dev_cancel","channel":"alpha","call_id":"dr_owned","timeout_ms":15000}`))
		errCh <- e
	}()
	select {
	case <-devCancel.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch on devCancel")
	}

	// Another extension on same manager AND same extension name on another manager are both denied.
	for _, attempt := range [][2]string{{"mgr-1", "ext-intruder"}, {"mgr-2", "ext-owner"}} {
		cancelRes, err := h.hubDeviceRelayCancel(attempt[0], attempt[1], json.RawMessage(`{"device_id":"dev_cancel","channel":"alpha","call_id":"dr_owned","reason":"hijack"}`))
		if cancelRes.Cancelled || !errors.As(err, &re) || re.Code != "permission_denied" {
			t.Fatalf("cross-owner cancel (%v): res=%+v err=%v, want permission_denied", attempt, cancelRes, err)
		}
	}
	select {
	case stray := <-devCancel.out:
		t.Fatalf("denied cross-extension cancel emitted WS frame: %s", stray)
	case <-time.After(50 * time.Millisecond):
	}

	// Owning extension cancels cleanly.
	cancelRes, err := h.hubDeviceRelayCancel("mgr-1", "ext-owner", json.RawMessage(`{"device_id":"dev_cancel","channel":"alpha","call_id":"dr_owned","reason":"user_stop"}`))
	if err != nil || !cancelRes.Cancelled {
		t.Fatalf("owner cancel: res=%+v err=%v", cancelRes, err)
	}
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "cancelled" {
			t.Fatalf("owner cancelled call err = %v, want cancelled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("in-flight call did not wake on owner cancel")
	}
	select {
	case raw := <-devCancel.out:
		var frame deviceRelayCancelFrame
		if err := json.Unmarshal(raw, &frame); err != nil {
			t.Fatal(err)
		}
		if frame.Type != "device_relay_cancel" || frame.Channel != "alpha" || frame.CallID != "dr_owned" || frame.Reason != "user_stop" {
			t.Fatalf("unexpected cancel frame: %+v", frame)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("missing device_relay_cancel frame")
	}

	// Second cancel on already-finished call is an idempotent no-op.
	again, err := h.hubDeviceRelayCancel("mgr-1", "ext-owner", json.RawMessage(`{"device_id":"dev_cancel","channel":"alpha","call_id":"dr_owned"}`))
	if err != nil || again.Cancelled {
		t.Fatalf("repeat cancel = %+v, err=%v, want cancelled=false", again, err)
	}

	// 4. Timeout (minimum 1000ms) returns timeout and sends device_relay_cancel(reason="timeout").
	go func() {
		_, e := h.hubDeviceRelayCall(context.Background(), "mgr-1", "ext-owner", json.RawMessage(`{"device_id":"dev_cancel","channel":"alpha","call_id":"dr_timeout","timeout_ms":1000}`))
		errCh <- e
	}()
	select {
	case <-devCancel.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch for timeout test")
	}
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "timeout" {
			t.Fatalf("timeout err = %v, want timeout", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("call did not time out within 3s")
	}
	select {
	case raw := <-devCancel.out:
		var frame deviceRelayCancelFrame
		if err := json.Unmarshal(raw, &frame); err != nil {
			t.Fatal(err)
		}
		if frame.Type != "device_relay_cancel" || frame.Channel != "alpha" || frame.CallID != "dr_timeout" || frame.Reason != "timeout" {
			t.Fatalf("unexpected timeout cancel frame: %+v", frame)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("missing timeout cancel frame")
	}
}
