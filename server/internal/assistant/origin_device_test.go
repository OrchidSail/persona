package assistant

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
)

func TestConnectorCallContextOriginDeviceLifecycle(t *testing.T) {
	ctx := context.Background()
	s, _, _ := infoUseService(t)

	// Initially empty
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "" {
		t.Fatalf("initial OriginDeviceID = %q, want empty", got.OriginDeviceID)
	}

	// Turn 1 from Android phone A via SendParams
	if _, err := s.SendParams(ctx, SendParams{
		MachineID:      "m1",
		SessionID:      "qa-main",
		Params:         map[string]any{"text": "check battery"},
		Human:          true,
		OriginDeviceID: "dev_android_a",
	}); err != nil {
		t.Fatalf("turn 1 SendParams: %v", err)
	}
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "dev_android_a" || got.Track != "main" {
		t.Fatalf("after turn 1: %+v", got)
	}

	// Node empty-params probe (!said) must NOT clobber the current turn's origin device
	if _, err := s.SendParams(ctx, SendParams{
		MachineID: "m1",
		SessionID: "qa-main",
		Params:    map[string]any{},
	}); err != nil {
		t.Fatalf("probe SendParams: %v", err)
	}
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "dev_android_a" {
		t.Fatalf("empty probe clobbered OriginDeviceID: got %q, want dev_android_a", got.OriginDeviceID)
	}

	// Turn 2 from Android phone B replaces phone A immediately
	if _, err := s.SendParams(ctx, SendParams{
		MachineID:      "m1",
		SessionID:      "qa-main",
		Params:         map[string]any{"text": "take screenshot"},
		Human:          true,
		OriginDeviceID: "dev_android_b",
	}); err != nil {
		t.Fatalf("turn 2 SendParams: %v", err)
	}
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "dev_android_b" {
		t.Fatalf("after turn 2: got %q, want dev_android_b", got.OriginDeviceID)
	}

	// Turn 3 from Web (paired non-Android device) replaces phone B so Android-only connectors fall back to default_device
	if _, err := s.SendParams(ctx, SendParams{
		MachineID:      "m1",
		SessionID:      "qa-main",
		Params:         map[string]any{"text": "run task on default phone"},
		Human:          true,
		OriginDeviceID: "dev_web_browser1",
	}); err != nil {
		t.Fatalf("turn 3 SendParams: %v", err)
	}
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "dev_web_browser1" {
		t.Fatalf("after turn 3: got %q, want dev_web_browser1", got.OriginDeviceID)
	}

	// Turn 4 from channel / non-device trigger (OriginDeviceID == "") clears previous device (zero staleness)
	if _, err := s.SendParams(ctx, SendParams{
		MachineID: "m1",
		SessionID: "qa-main",
		Params:    map[string]any{"text": "message from channel"},
	}); err != nil {
		t.Fatalf("turn 4 SendParams: %v", err)
	}
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "" {
		t.Fatalf("after channel turn: got %q, want empty", got.OriginDeviceID)
	}

	// Re-set to phone A, then simulate background/scheduled/supervision relay clearing it
	s.setConversationOriginDevice("qa-main", "dev_android_a")
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "dev_android_a" {
		t.Fatalf("re-set: got %q", got.OriginDeviceID)
	}
	s.clearConversationOriginDevice("qa-main")
	if got := s.connectorCallContext(ctx, "qa-main"); got.OriginDeviceID != "" {
		t.Fatalf("after clearConversationOriginDevice: got %q, want empty", got.OriginDeviceID)
	}

	// Unknown conversation still returns empty CallContext even if an ID was set
	s.setConversationOriginDevice("qa-nowhere", "dev_android_a")
	if got := s.connectorCallContext(ctx, "qa-nowhere"); got.Track != "" || got.OriginDeviceID != "" {
		t.Fatalf("unknown conversation: %+v", got)
	}
}

func TestSanitizeOriginDeviceID(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"dev_0123456789ab", "dev_0123456789ab"},
		{"  dev_trimmed  ", "dev_trimmed"},
		{"", ""},
		{"   ", ""},
		{"dev with space", ""},
		{"dev\nnewline", ""},
		{"dev\x00nul", ""},
		{strings.Repeat("a", 129), ""},
		{strings.Repeat("a", 128), strings.Repeat("a", 128)},
	}
	for _, tc := range cases {
		if got := sanitizeOriginDeviceID(tc.in); got != tc.want {
			t.Errorf("sanitizeOriginDeviceID(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestConversationOriginDeviceConcurrent(t *testing.T) {
	s := &Service{}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				sid := fmt.Sprintf("conv-%d", j%4)
				dev := fmt.Sprintf("dev_%d_%d", worker, j)
				s.setConversationOriginDevice(sid, dev)
				_ = s.conversationOriginDevice(sid)
				if j%5 == 0 {
					s.clearConversationOriginDevice(sid)
				}
			}
		}(i)
	}
	wg.Wait()
}
