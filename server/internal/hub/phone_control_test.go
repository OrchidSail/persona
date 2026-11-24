package hub

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/nwverse/persona/sdk/go/wire"
	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/db"
	"github.com/nwverse/persona/server/internal/extwire"
	"github.com/nwverse/persona/server/internal/rpc"
)

func addTestPhoneControlClient(t *testing.T, h *Hub, deviceID, role string, sim bool, since time.Time) (*client, chan struct{}) {
	t.Helper()
	done := make(chan struct{})
	c := &client{
		hub:      h,
		out:      make(chan []byte, 32),
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

func registerPhoneControlFrame(deviceID, name, model string, enabled, ready bool) []byte {
	b, _ := json.Marshal(map[string]any{
		"type":             "register_phone_control",
		"protocol_version": 1,
		"device_id":        deviceID,
		"device_name":      name,
		"manufacturer":     "samsung",
		"model":            model,
		"android_release":  "16",
		"android_sdk":      36,
		"app_version":      "1.2-overlay",
		"enabled":          enabled,
		"ready":            ready,
		"capabilities":     []string{"device_state", "observe", "screenshot_a11y_jpeg", "action", "session", "otp_consume", "fill_verification_code"},
		"permissions": map[string]any{
			"accessibility":                ready,
			"notification_listener":        true,
			"overlay":                      true,
			"screen_capture":               false,
			"battery_optimization_ignored": true,
		},
		"safety_policy": map[string]any{
			"allow_notification_otp_auto_login":      true,
			"block_real_payment":                     true,
			"allow_normal_actions_directly":          true,
			"require_phone_for_biometric_or_captcha": true,
		},
		"runtime": map[string]any{
			"interactive":            true,
			"keyguard_showing":       false,
			"device_locked":          false,
			"overlay_active":         false,
			"virtual_display_active": false,
			"resident_connection":    true,
		},
	})
	return b
}

func TestPhoneControlRegistrationIsolationAndDiscovery(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	// Answer-only device cannot register phone control.
	watch, _ := addTestPhoneControlClient(t, h, "dev_watch", roleAnswer, false, now)
	h.onClientMessage(context.Background(), watch, registerPhoneControlFrame("dev_watch", "Watch", "Watch", true, true))

	// Simulator, non-ready real phone, and two overlapping connections for the primary real phone.
	simClient, _ := addTestPhoneControlClient(t, h, "dev_sim", roleFull, true, now.Add(4*time.Second))
	notReady, _ := addTestPhoneControlClient(t, h, "dev_notready", roleFull, false, now.Add(2*time.Second))
	oldPrimary, _ := addTestPhoneControlClient(t, h, "dev_primary", roleFull, false, now.Add(1*time.Second))
	newPrimary, _ := addTestPhoneControlClient(t, h, "dev_primary", roleFull, false, now.Add(3*time.Second))

	h.mu.Lock()
	h.primaryPhone = phonePrimary{CredentialID: "dev_primary", Name: "Primary Phone"}
	h.mu.Unlock()

	h.onClientMessage(context.Background(), simClient, registerPhoneControlFrame("dev_sim", "Emulator", "sdk_gphone64", true, true))
	h.onClientMessage(context.Background(), notReady, registerPhoneControlFrame("dev_notready", "Galaxy S", "SM-S936B", true, false))
	h.onClientMessage(context.Background(), oldPrimary, registerPhoneControlFrame("dev_primary", "Old Conn", "SM-A165F", true, true))
	h.onClientMessage(context.Background(), newPrimary, registerPhoneControlFrame("dev_primary", "Galaxy A", "SM-A165F", true, true))

	// Structural isolation: phoneControl never contaminates h.phoneTools, phoneToolList, or phonePrimaryView.
	if got := h.phoneToolList(); len(got) != 0 {
		t.Fatalf("phoneControl polluted phoneToolList: %+v", got)
	}
	h.mu.Lock()
	if len(h.phoneTools) != 0 {
		h.mu.Unlock()
		t.Fatalf("phoneControl polluted h.phoneTools: %+v", h.phoneTools)
	}
	h.mu.Unlock()
	view := h.phonePrimaryView([]db.DeviceToken{{ID: "dev_primary"}})
	if len(view.Candidates) != 0 {
		t.Fatalf("phoneControl polluted phonePrimaryView candidates: %+v", view.Candidates)
	}

	// Default include_not_ready=false filters out notReady, deduplicates dev_primary to newest conn, and sorts real+primary before simulator.
	raw, handled, err := h.extAppCall(context.Background(), "", assistantwire.AppCall{
		AppID:  extwire.AppHub,
		Method: extwire.HubPhoneControlDevices,
		Params: json.RawMessage(`{"extension":"persona-phone","request":{}}`),
	})
	if !handled || err != nil {
		t.Fatalf("devices(default): handled=%v err=%v", handled, err)
	}
	var res wire.PhoneControlDevicesResult
	if err := json.Unmarshal(raw, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Devices) != 2 {
		t.Fatalf("got %d ready devices, want 2: %+v", len(res.Devices), res.Devices)
	}
	if res.Devices[0].DeviceID != "dev_primary" || res.Devices[0].Name != "Galaxy A" || !res.Devices[0].Primary {
		t.Fatalf("devices[0] = %+v, want newest primary Galaxy A", res.Devices[0])
	}
	if res.Devices[1].DeviceID != "dev_sim" {
		t.Fatalf("devices[1] = %+v, want simulator second", res.Devices[1])
	}

	// include_not_ready=true returns all 3 full devices ordered real+primary -> real+not-ready -> simulator.
	raw, handled, err = h.extAppCall(context.Background(), "", assistantwire.AppCall{
		AppID:  extwire.AppHub,
		Method: extwire.HubPhoneControlDevices,
		Params: json.RawMessage(`{"extension":"persona-phone","request":{"include_not_ready":true}}`),
	})
	if !handled || err != nil {
		t.Fatalf("devices(include_not_ready): handled=%v err=%v", handled, err)
	}
	res = wire.PhoneControlDevicesResult{}
	if err := json.Unmarshal(raw, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Devices) != 3 {
		t.Fatalf("got %d devices, want 3: %+v", len(res.Devices), res.Devices)
	}
	if res.Devices[0].DeviceID != "dev_primary" || res.Devices[1].DeviceID != "dev_notready" || res.Devices[2].DeviceID != "dev_sim" {
		t.Fatalf("unexpected device order: %+v", res.Devices)
	}
}

func TestPhoneControlTargetedRoutingAndNoCrossDeviceFallback(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	phoneA, _ := addTestPhoneControlClient(t, h, "dev_a", roleFull, false, now)
	phoneB, _ := addTestPhoneControlClient(t, h, "dev_b", roleFull, false, now.Add(time.Second))
	disabledPhone, _ := addTestPhoneControlClient(t, h, "dev_off", roleFull, false, now.Add(2*time.Second))

	h.mu.Lock()
	h.primaryPhone = phonePrimary{CredentialID: "dev_a", Name: "Phone A"}
	h.mu.Unlock()

	h.onClientMessage(context.Background(), phoneA, registerPhoneControlFrame("dev_a", "Phone A", "ModelA", true, true))
	h.onClientMessage(context.Background(), phoneB, registerPhoneControlFrame("dev_b", "Phone B", "ModelB", true, true))
	h.onClientMessage(context.Background(), disabledPhone, registerPhoneControlFrame("dev_off", "Phone Off", "ModelOff", false, false))

	// Calling an unknown device_id must return device_not_found, NEVER falling back to primary dev_a.
	_, err := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_missing","op":"device_state"}`))
	var re *rpc.Error
	if !errors.As(err, &re) || re.Code != "device_not_found" {
		t.Fatalf("missing device: got %v, want device_not_found", err)
	}

	// Calling a connected phone with control disabled returns control_disabled.
	_, err = h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_off","op":"device_state"}`))
	if !errors.As(err, &re) || re.Code != "control_disabled" {
		t.Fatalf("disabled device: got %v, want control_disabled", err)
	}

	// Invalid params rejected before reaching any phone.
	for _, bad := range []string{
		`{"device_id":"dev_b","op":"unknown_op"}`,
		`{"device_id":"dev_b","op":"observe","extra":true}`,
		`{"device_id":"dev_b","op":"observe","args":[1,2,3]}`,
		`{"device_id":"","op":"observe"}`,
		`{"device_id":"dev_b","op":"observe","timeout_ms":999999}`,
	} {
		_, err = h.hubPhoneControlCall(context.Background(), json.RawMessage(bad))
		if !errors.As(err, &re) || re.Code != "invalid_params" {
			t.Fatalf("call(%s): got %v, want invalid_params", bad, err)
		}
	}

	// Targeted dispatch to dev_b goes ONLY to phoneB, and phoneA cannot spoof phoneB's result.
	done := make(chan wire.PhoneControlCallResult, 1)
	errCh := make(chan error, 1)
	go func() {
		r, e := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_b","op":"observe","call_id":"pc_target_b","idempotency_key":"k1","args":{"step":1}}`))
		if e != nil {
			errCh <- e
			return
		}
		done <- r
	}()

	var dispatched phoneControlCallFrame
	select {
	case raw := <-phoneB.out:
		if err := json.Unmarshal(raw, &dispatched); err != nil {
			t.Fatal(err)
		}
	case raw := <-phoneA.out:
		t.Fatalf("call routed to wrong phoneA: %s", raw)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for dispatch on phoneB")
	}
	if dispatched.Type != "phone_control_call" || dispatched.CallID != "pc_target_b" || dispatched.Op != "observe" || dispatched.IdempotencyKey != "k1" {
		t.Fatalf("unexpected dispatched frame: %+v", dispatched)
	}

	// Spoofed result from phoneA must be ignored.
	spoof, _ := json.Marshal(map[string]any{"type": "phone_control_result", "call_id": "pc_target_b", "ok": true, "result": map[string]any{"spoofed": true}})
	h.onClientMessage(context.Background(), phoneA, spoof)
	select {
	case r := <-done:
		t.Fatalf("spoofed phoneA response was accepted: %+v", r)
	case <-time.After(50 * time.Millisecond):
	}

	// Genuine result from phoneB succeeds.
	realResp, _ := json.Marshal(map[string]any{"type": "phone_control_result", "call_id": "pc_target_b", "ok": true, "result": map[string]any{"snapshot_id": "snap_1"}})
	h.onClientMessage(context.Background(), phoneB, realResp)
	select {
	case err := <-errCh:
		t.Fatalf("unexpected call error: %v", err)
	case res := <-done:
		if !res.Ok || res.CallID != "pc_target_b" || res.DeviceID != "dev_b" || string(res.Result) != `{"snapshot_id":"snap_1"}` {
			t.Fatalf("unexpected call result: %+v", res)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for result")
	}
}

func TestPhoneControlFastFailOnDisconnectDisableAndCancel(t *testing.T) {
	h := newTestHub(t)
	now := time.Now().UTC()

	// 1. Disconnect mid-call fails immediately with device_disconnected (does not wait for 20s timeout).
	phone1, done1 := addTestPhoneControlClient(t, h, "dev_drop", roleFull, false, now)
	h.onClientMessage(context.Background(), phone1, registerPhoneControlFrame("dev_drop", "Drop Phone", "M1", true, true))

	errCh := make(chan error, 1)
	go func() {
		_, err := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_drop","op":"action","call_id":"pc_drop","timeout_ms":20000,"args":{"action":"tap"}}`))
		errCh <- err
	}()
	select {
	case <-phone1.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch")
	}
	start := time.Now()
	close(done1)
	h.dropClient(phone1)

	select {
	case err := <-errCh:
		var re *rpc.Error
		if !errors.As(err, &re) || re.Code != "device_disconnected" {
			t.Fatalf("disconnect err = %v, want device_disconnected", err)
		}
		if time.Since(start) > 2*time.Second {
			t.Fatalf("disconnect took too long: %v", time.Since(start))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("call hung after phone disconnected")
	}

	// Subsequent call to previously-seen disconnected device returns device_offline.
	_, err := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_drop","op":"device_state"}`))
	var re *rpc.Error
	if !errors.As(err, &re) || re.Code != "device_offline" {
		t.Fatalf("after disconnect: got %v, want device_offline", err)
	}

	// 2. Disabling control on phone mid-call fails immediately with control_disabled.
	phone2, _ := addTestPhoneControlClient(t, h, "dev_toggle", roleFull, false, now.Add(time.Second))
	h.onClientMessage(context.Background(), phone2, registerPhoneControlFrame("dev_toggle", "Toggle Phone", "M2", true, true))
	go func() {
		_, err := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_toggle","op":"observe","call_id":"pc_toggle","timeout_ms":15000}`))
		errCh <- err
	}()
	select {
	case <-phone2.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch on phone2")
	}
	h.onClientMessage(context.Background(), phone2, registerPhoneControlFrame("dev_toggle", "Toggle Phone", "M2", false, false))
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "control_disabled" {
			t.Fatalf("disable err = %v, want control_disabled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("call hung after control disabled")
	}

	// 3. Context cancel sends phone_control_cancel to phone and returns cancelled.
	phone3, _ := addTestPhoneControlClient(t, h, "dev_cancel", roleFull, false, now.Add(2*time.Second))
	h.onClientMessage(context.Background(), phone3, registerPhoneControlFrame("dev_cancel", "Cancel Phone", "M3", true, true))
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		_, err := h.hubPhoneControlCall(ctx, json.RawMessage(`{"device_id":"dev_cancel","op":"observe","call_id":"pc_ctx_cancel","timeout_ms":15000}`))
		errCh <- err
	}()
	select {
	case <-phone3.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing dispatch on phone3")
	}
	cancel()
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "cancelled" {
			t.Fatalf("ctx cancel err = %v, want cancelled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("call did not exit on ctx cancel")
	}
	select {
	case raw := <-phone3.out:
		var frame phoneControlCancelFrame
		if err := json.Unmarshal(raw, &frame); err != nil {
			t.Fatal(err)
		}
		if frame.Type != "phone_control_cancel" || frame.CallID != "pc_ctx_cancel" || frame.Reason != "caller_cancelled" {
			t.Fatalf("unexpected cancel frame: %+v", frame)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("missing phone_control_cancel frame")
	}

	// 4. Explicit phone.control.cancel RPC cancels in-flight call and forwards phone_control_cancel.
	go func() {
		_, err := h.hubPhoneControlCall(context.Background(), json.RawMessage(`{"device_id":"dev_cancel","op":"action","call_id":"pc_rpc_cancel","timeout_ms":15000}`))
		errCh <- err
	}()
	select {
	case <-phone3.out:
	case <-time.After(2 * time.Second):
		t.Fatal("missing second dispatch on phone3")
	}
	cancelRes, err := h.hubPhoneControlCancel(json.RawMessage(`{"device_id":"dev_cancel","call_id":"pc_rpc_cancel","reason":"user_stop"}`))
	if err != nil || !cancelRes.Cancelled {
		t.Fatalf("hubPhoneControlCancel = %+v, err=%v", cancelRes, err)
	}
	select {
	case err := <-errCh:
		if !errors.As(err, &re) || re.Code != "cancelled" {
			t.Fatalf("rpc cancel err = %v, want cancelled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("in-flight call did not wake on phone.control.cancel")
	}
	select {
	case raw := <-phone3.out:
		var frame phoneControlCancelFrame
		if err := json.Unmarshal(raw, &frame); err != nil {
			t.Fatal(err)
		}
		if frame.Type != "phone_control_cancel" || frame.CallID != "pc_rpc_cancel" || frame.Reason != "user_stop" {
			t.Fatalf("unexpected rpc cancel frame: %+v", frame)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("missing phone_control_cancel frame after RPC cancel")
	}
}
