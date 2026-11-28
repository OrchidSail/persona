package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/channel"
	"github.com/nwverse/persona/server/internal/rpc"
)

func TestChannelReceiveColdSettingsReadFailureCanRetry(t *testing.T) {
	_, f, _ := threadsChannelService(t, true)
	st := &memStore{}
	st.setRawSetting(settingsKey, []byte(`{"main":"m1","machine_id":"server","integrations_v2":false}`))
	st.setRawSetting(channelsKey, []byte(`{"examplechat":{"thread_mode":"channel_default"}}`))
	st.failKey(settingsKey, errors.New("temporarily unavailable"))
	s := New(Options{Hub: f, Store: func() Store { return st }, Logf: testLogf(t)})
	receive := func() error {
		return s.ChannelReceive(t.Context(), ChannelReceive{
			Channel: "examplechat",
			Message: json.RawMessage(`{"id":"retry","text":"continue the group","chat_id":"group","chat_kind":"group","thread":"old-thread"}`),
		})
	}
	err := receive()
	var retry *rpc.Error
	if !errors.As(err, &retry) || retry.Code != CodeTryAgain {
		t.Fatalf("cold receipt = %v, want try_again", err)
	}
	if _, found := st.rawSetting(channelsStateKey); found {
		t.Fatal("unreadable settings consumed the message ID")
	}
	for _, path := range []string{"/api/v1/assistant", "/api/v1/channels", "/api/v1/channels/examplechat"} {
		if w := channelAPIRequest(t, s, http.MethodGet, path, "full", ""); w.Code != http.StatusServiceUnavailable {
			t.Errorf("GET %s with unreadable settings = %d, want 503", path, w.Code)
		}
	}
	st.failKey(settingsKey, nil)
	if err := receive(); err != nil {
		t.Fatal(err)
	}
	waitAdmissionDrained(t, s)
	if f.sent() != 1 || boundThread(s, "old-thread") == "" || lastSendOnceSession(f) != "" {
		t.Fatalf("retry bypassed the saved opt-out: starts=%d route=%q", f.sent(), lastSendOnceSession(f))
	}
	assertIntegrationsV2Flag(t, s, false)
	s.prefs.mu.Lock()
	s.prefs.at = time.Time{}
	s.prefs.mu.Unlock()
	st.failKey(settingsKey, errors.New("temporarily unavailable again"))
	assertIntegrationsV2Flag(t, s, false)
	if on, err := s.integrationsV2On(t.Context()); err != nil || on {
		t.Fatalf("warm read failure lost the last good opt-out: on=%v err=%v", on, err)
	}
}

func TestUnconfiguredChannelKeepsLegacyGroupRouting(t *testing.T) {
	s, f, _ := threadsChannelService(t, true)
	ctx := t.Context()
	const key = "legacy-group-thread"
	s.seedThreadBinding("examplechat", key, "server", "existing")
	registerAssistantSession(f, "existing")
	receive := func(id, text string) {
		t.Helper()
		m := channel.Message{ID: id, Text: text, ChatID: "group", ChatKind: channel.ChatKindGroup, Thread: key}
		raw, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := s.ChannelReceive(ctx, ChannelReceive{Channel: "examplechat", Message: raw}); err != nil {
			t.Fatal(err)
		}
		waitAdmissionDrained(t, s)
	}
	assertIntegrationsV2Flag(t, s, true)
	receive("continue", "continue the group")
	if got := lastSendOnceSession(f); got != "existing" {
		t.Fatalf("group delivered to %q, want existing", got)
	}
	receive("reset", "/new")
	if boundThread(s, key) != "" {
		t.Fatal("group /new left its legacy thread bound")
	}
	receive("fresh", "start a fresh group conversation")
	fresh := boundThread(s, key)
	if fresh == "" || fresh == "existing" || f.sent() != 1 {
		t.Fatalf("group after /new: binding=%q starts=%d", fresh, f.sent())
	}
	if rt, err := s.channelTarget(ctx, "examplechat"); err != nil || !rt.main || rt.session.SessionID != "m1" {
		t.Fatalf("group /new moved the default target: %+v, %v", rt, err)
	}
	registerAssistantSession(f, fresh)
	previous := true
	for i, on := range []bool{false, true} {
		setIntegrationsV2ViaHTTP(t, s, on, previous)
		previous = on
		receive(fmt.Sprintf("toggle-%d", i), "continue after toggling Settings")
		if got := lastSendOnceSession(f); got != fresh {
			t.Fatalf("integrations_v2=%t: group delivered to %q, want %q", on, got, fresh)
		}
	}
}

func TestChannelRouteKeyPartialClassification(t *testing.T) {
	for _, tc := range []struct {
		name              string
		kind              channel.ChatKind
		chat, topic, want string
	}{
		{"direct with stray topic", channel.ChatKindDirect, "chat", "topic", ""},
		{"topic without chat", channel.ChatKindTopic, " ", "topic", "legacy"},
		{"chat alone", "", "chat", "", "legacy"},
		{"future kind", "future", "chat", "", "legacy"},
		{"topic without topic ID", channel.ChatKindTopic, "chat", "", "legacy"},
		{"topic pair without kind", "", "chat", "topic", channelTopicRouteKey("chat", "topic")},
	} {
		for _, mode := range []channel.ThreadMode{channel.ThreadModePerTopic, channel.ThreadModePerGroupAndTopic} {
			routing := threadRouting(channel.View{}, mode, true)
			m := channel.Message{ChatKind: tc.kind, ChatID: tc.chat, TopicID: tc.topic, Thread: " legacy "}
			if got := routing.keys(m).primary; got != tc.want {
				t.Errorf("%s (%s): got %q, want %q", tc.name, mode, got, tc.want)
			}
			m.Thread = ""
			if tc.want == "legacy" && routing.keys(m).primary != "" {
				t.Errorf("%s (%s): incomplete route without a legacy thread must use the default target", tc.name, mode)
			}
		}
	}
	if got := channelThreadIdleTTL(true, &channelPrefs{ThreadIdleHours: channel.MaxThreadIdleHours + 1}); got != channel.MaxThreadIdleHours*time.Hour {
		t.Errorf("loaded idle timeout = %v", got)
	}
}

func TestChannelRoutingDefaultsAcrossManagers(t *testing.T) {
	// Both managers see the same channel ID and capability but own separate settings and bindings.
	newManager := func(id string) (*Service, *fakeHub, *memStore) {
		_, f, _ := threadsChannelService(t, true)
		st := &memStore{}
		return withSettings(t, f, New(Options{ID: id, Hub: f, Store: func() Store { return st }, Logf: testLogf(t)})), f, st
	}
	a, hubA, storeA := newManager("assistant-a")
	b, _, _ := newManager("assistant-b")
	check := func(s *Service, on bool, mode channel.ThreadMode, override, key string, idle int) {
		t.Helper()
		assertIntegrationsV2Flag(t, s, on)
		list := getChannelListByID(t, s)["examplechat"]
		d := getChannelDetail(t, s, "examplechat")
		if list.ThreadMode != mode || d.ThreadMode != mode || d.ThreadIdleHours != idle {
			t.Fatalf("%s: list mode %q, detail mode %q, idle %d", s.id, list.ThreadMode, d.ThreadMode, d.ThreadIdleHours)
		}
		if override == "" && d.TargetOverride != nil || override != "" && (d.TargetOverride == nil || d.TargetOverride.Mode != override) {
			t.Fatalf("%s: target override %+v, want %q", s.id, d.TargetOverride, override)
		}
		msg := channel.Message{Thread: "legacy", ChatID: "chat", TopicID: "topic"}
		binding, _, err := s.threadBindingForTest(context.Background(), "examplechat", msg)
		if err != nil || binding.isThread() != (key != "") || binding.topic != s.storedThreadBindingForTest("examplechat", key, on).topic {
			t.Fatalf("%s: route %q, want identity for %q, err=%v", s.id, binding.topic, key, err)
		}
	}
	topic := channelTopicRouteKey("chat", "topic")
	check(a, true, channel.ThreadModePerTopic, channel.TargetOverrideThreads, topic, 0)
	check(b, true, channel.ThreadModePerTopic, channel.TargetOverrideThreads, topic, 0)
	rec := channelAPIRequest(t, a, http.MethodPut, "/api/v1/channels/examplechat", "full", `{"thread_mode":"channel_default","thread_idle_hours":7}`)
	if rec.Code != http.StatusOK {
		t.Fatal(rec.Code, rec.Body.String())
	}
	setIntegrationsV2ViaHTTP(t, a, false, true)
	check(a, false, channel.ThreadModeChannelDefault, channel.TargetOverrideThreads, "legacy", 7)
	check(b, true, channel.ThreadModePerTopic, channel.TargetOverrideThreads, topic, 0)
	a = New(Options{ID: a.id, Hub: hubA, Store: func() Store { return storeA }, Logf: testLogf(t)})
	check(a, false, channel.ThreadModeChannelDefault, channel.TargetOverrideThreads, "legacy", 7)
	setIntegrationsV2ViaHTTP(t, a, true, false)
	check(a, true, channel.ThreadModeChannelDefault, "", "", 7)
	check(b, true, channel.ThreadModePerTopic, channel.TargetOverrideThreads, topic, 0)
}

func TestIntegrationsV2PartialAssistantPUT(t *testing.T) {
	for _, tc := range []struct {
		name   string
		stored string
		on     bool
	}{
		{name: "unset", on: true},
		{name: "explicitly off", stored: "false", on: false},
		{name: "explicitly on", stored: "true", on: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			st := &memStore{}
			s := newServiceOn(t, newFakeHub().withMachine(), st)
			if tc.stored != "" {
				setIntegrationsV2ViaHTTP(t, s, tc.on, true)
			}
			for _, body := range []string{`{"notes":"updated"}`, `{"timezone":"UTC"}`, `{"todos_v2":true}`, `{"labs_seen":["todos_v2"]}`, `{"integrations_v2":null}`} {
				w, state := permissionSettingsRequest(t, s, http.MethodPut, "/api/v1/assistant", s.managerIdentity(), "full", body)
				if w.Code != http.StatusOK || state.IntegrationsV2 != tc.on {
					t.Fatalf("PUT %s: %d %s", body, w.Code, w.Body.String())
				}
				var settings map[string]json.RawMessage
				if found, err := st.Setting(t.Context(), settingsKey, &settings); !found || err != nil {
					t.Fatalf("read settings after PUT %s: found=%t err=%v", body, found, err)
				}
				if got := string(settings["integrations_v2"]); got != tc.stored {
					t.Fatalf("PUT %s stored integrations_v2 as %q, want %q", body, got, tc.stored)
				}
			}
			assertIntegrationsV2Flag(t, s, tc.on)
		})
	}
}

func TestChannelRoutingPUTRequiresReadablePrefs(t *testing.T) {
	_, f, _ := threadsChannelService(t, true)
	st := &memStore{}
	st.setRawSetting(channelsKey, []byte(`{"examplechat":{"thread_mode":"per_topic"}}`))
	st.failKey(channelsKey, errors.New("temporarily unavailable"))
	s := newServiceOn(t, f, st)
	put := func() int {
		return channelAPIRequest(t, s, http.MethodPut, "/api/v1/channels/examplechat", "full", `{"thread_idle_hours":8}`).Code
	}
	if got := put(); got != http.StatusServiceUnavailable {
		t.Fatalf("PUT with unreadable prefs: %d", got)
	}
	st.failKey(channelsKey, nil)
	if got := put(); got != http.StatusOK {
		t.Fatalf("retry: %d", got)
	}
	if d := getChannelDetail(t, newServiceOn(t, f, st), "examplechat"); d.ThreadIdleHours != 8 || d.ThreadMode != channel.ThreadModePerTopic {
		t.Fatalf("reloaded: %+v", d.View)
	}
}

func TestMentionChannelRoutingAndIdleHoursHTTP(t *testing.T) {
	s, f, _ := threadsChannelService(t, true)
	f.answerApp(AppChannels, ChannelsList, func(json.RawMessage) (any, error) {
		return map[string]any{"channels": []channel.View{{ID: "mention", Kind: channel.KindMention, Source: channel.SourcePlugin, Installed: true, Capabilities: channel.Capabilities{Threads: true}}}}, nil
	})
	if d := getChannelDetail(t, s, "mention"); d.ThreadMode != channel.ThreadModePerTopic || d.Target == nil || d.Target.Kind != channel.TargetNew {
		t.Fatal(d.View)
	}
	for _, tc := range []struct {
		value string
		code  int
	}{{"87600", 200}, {"87601", 400}, {"1.5", 400}, {"null", 200}} {
		rec := channelAPIRequest(t, s, http.MethodPut, "/api/v1/channels/mention", "full", fmt.Sprintf(`{"thread_idle_hours":%s}`, tc.value))
		if rec.Code != tc.code {
			t.Fatalf("PUT %s: %d %s", tc.value, rec.Code, rec.Body.String())
		}
		if d := getChannelDetail(t, s, "mention"); d.ThreadIdleHours != channel.MaxThreadIdleHours || d.ThreadMode != channel.ThreadModePerTopic {
			t.Fatalf("after PUT %s: %+v", tc.value, d.View)
		}
	}
	if err := s.ChannelReceive(context.Background(), ChannelReceive{Channel: "mention", Message: json.RawMessage(`{"id":"mention-1","owner":true,"text":"hello","chat_id":"chat","topic_id":"topic"}`)}); err != nil {
		t.Fatal(err)
	}
	waitAdmissionDrained(t, s)
	if got := boundThreadFor(s, "mention", channelTopicRouteKey("chat", "topic")); got == "" {
		t.Fatal("owner mention did not start a topic conversation")
	}
}

func TestFeishuTopicRoutingAcrossIntegrationsToggle(t *testing.T) {
	needsLarkLayer(t)
	e := newThreadEnv(t, nil)
	// The extension manager supplies this override for an opted-in topic chat.
	e.f.answerApp(AppChannels, ChannelsList, func(json.RawMessage) (any, error) {
		return map[string]any{
			"channels":         []channel.View{{ID: channel.Feishu, Kind: channel.KindIM, Source: channel.SourceBuiltin, Installed: true}},
			"target_overrides": map[string]*channel.TargetOverride{channel.Feishu: {Mode: channel.TargetOverrideTopics}},
		}, nil
	})
	putTodo(e.s, Todo{ID: "topic-todo", Title: "Topic", Origin: "c1x1x1", Thread: &TodoThread{Channel: channel.Feishu, ChatID: testChat, ThreadID: testThread}})
	for i, on := range []bool{false, true, false} {
		setIntegrationsV2(t, e.s, on)
		rec := channelAPIRequest(t, e.s, http.MethodGet, "/api/v1/channels/feishu", "full", "")
		var fields map[string]json.RawMessage
		if rec.Code != http.StatusOK || json.Unmarshal(rec.Body.Bytes(), &fields) != nil || fields["thread_mode"] != nil {
			t.Fatalf("Feishu detail: %d %s", rec.Code, rec.Body.String())
		}
		var ov channel.TargetOverride
		if err := json.Unmarshal(fields["target_override"], &ov); err != nil || ov.Mode != channel.TargetOverrideTopics {
			t.Fatalf("Feishu override: %+v, %v", ov, err)
		}
		rec = channelAPIRequest(t, e.s, http.MethodPut, "/api/v1/channels/feishu", "full", `{"thread_idle_hours":12}`)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("Feishu PUT: %d", rec.Code)
		}
		raw, err := json.Marshal(topicEvent(fmt.Sprintf("topic-%d", i), "topic reply"))
		if err != nil {
			t.Fatal(err)
		}
		if err := e.s.FeishuMessage(context.Background(), FeishuMessage{Event: raw}); err != nil {
			t.Fatal(err)
		}
		waitFor(t, "Feishu topic handoff", func() bool { return len(handoffs(e.f)) == i+1 })
		if got := handoffs(e.f)[i]; got.machine != "server" || got.session != "c1x1x1" {
			t.Fatalf("v2=%t: handed off to %+v", on, got)
		}
	}
}
