package assistant

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/channel"
	"github.com/nwverse/persona/server/internal/rpc"
)

const (
	testLegacyThreadKey = "chat_example:topic_example"
	testTopicThreadKey  = "v2:topic:12:chat_example:topic_example"
)

func testDualThreadMessage() channel.Message {
	return channel.Message{Thread: testLegacyThreadKey, ChatID: "chat_example", ChatKind: channel.ChatKindTopic, TopicID: "topic_example"}
}

func TestChannelReceiveDualTopicContinuesFromLegacyKeyAfterReload(t *testing.T) {
	s, f, _ := threadsChannelService(t, true)
	assertChannelDualTopicContinuesFromLegacyKeyAfterReload(t, s, f)
}

func TestChannelReceiveDualTopicPersistenceInPostgres(t *testing.T) {
	store := todoTestDB(t)
	_, f, _ := threadsChannelService(t, true)
	s := newServiceOn(t, f, store)
	assertChannelDualTopicContinuesFromLegacyKeyAfterReload(t, s, f)
}

func assertChannelDualTopicContinuesFromLegacyKeyAfterReload(t *testing.T, s *Service, f *fakeHub) {
	t.Helper()
	send := func(s *Service, m channel.Message) {
		t.Helper()
		raw, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := s.ChannelReceive(t.Context(), ChannelReceive{Channel: "examplechat", Message: raw}); err != nil {
			t.Fatal(err)
		}
		waitAdmissionDrained(t, s)
	}
	dual := testDualThreadMessage()
	dual.ID, dual.Text = "first", "first topic message"
	send(s, dual)
	session := boundThread(s, testTopicThreadKey)
	if session == "" {
		t.Fatal("dual-format message has no binding")
	}
	registerAssistantSession(f, session)
	reloaded := newServiceOn(t, f, s.store())
	send(reloaded, channel.Message{ID: "second", Text: "legacy follow-up", Thread: testLegacyThreadKey})
	if f.sent() != 1 || lastSendOnceSession(f) != session {
		t.Fatalf("legacy follow-up after reload: starts=%d continuation=%q, want one start and %q", f.sent(), lastSendOnceSession(f), session)
	}

	// Structured coordinates still isolate topics if a plugin reuses an opaque key in another chat.
	dual.ID, dual.Text, dual.ChatID = "other", "different topic", "other_chat"
	send(reloaded, dual)
	if other := boundThread(reloaded, channelTopicRouteKey(dual.ChatID, dual.TopicID)); other == "" || other == session ||
		f.sent() != 2 || boundThread(reloaded, testLegacyThreadKey) != session {
		t.Fatalf("reused opaque key moved a different topic: first=%q other=%q", session, other)
	}
}

func TestChannelReceiveTopicBridgeRetriesUntilOldQueuesDrain(t *testing.T) {
	_, f, _ := threadsChannelService(t, true)
	store := &memStore{}
	now := time.Now().UTC()
	// Earlier managers could store the two aliases with different bindings.
	if err := store.PutSetting(t.Context(), channelsKey, map[string]channelPrefs{"examplechat": {Threads: map[string]*channelThread{
		testLegacyThreadKey: {MachineID: "server", SessionID: "legacy", At: now},
		testTopicThreadKey:  {MachineID: "server", SessionID: "structured", At: now},
	}}}); err != nil {
		t.Fatal(err)
	}
	s := newServiceOn(t, f, store)
	registerAssistantSession(f, "legacy")
	registerAssistantSession(f, "structured")
	dual := testDualThreadMessage()
	dual.ID, dual.Text = "bridge", "cross-format delivery"
	structured := dual
	structured.ID, structured.Thread = "prior", ""
	held, err := s.admitChannelMessage(t.Context(), "examplechat", structured)
	if err != nil || held == nil {
		t.Fatalf("reserve the old structured route: %v", err)
	}
	raw, err := json.Marshal(dual)
	if err != nil {
		t.Fatal(err)
	}
	receive := func() error {
		return s.ChannelReceive(t.Context(), ChannelReceive{Channel: "examplechat", Message: raw})
	}
	var retry *rpc.Error
	if err := receive(); !errors.As(err, &retry) || retry.Code != CodeTryAgain {
		t.Fatalf("merge while a formerly independent queue is active = %v, want try_again", err)
	}
	held.run(s, structured, "")
	if err := receive(); err != nil {
		t.Fatal(err)
	}
	waitAdmissionDrained(t, s)
	if boundThread(s, testTopicThreadKey) != "structured" || boundThread(s, testLegacyThreadKey) != "structured" || lastSendOnceSession(f) != "structured" {
		t.Fatal("redelivered bridge did not consolidate both aliases on the live structured binding")
	}
}

func TestChannelReceiveAliasWriteFailureCanRetry(t *testing.T) {
	s, f, _ := threadsChannelService(t, true)
	store := s.store().(*memStore)
	dual := testDualThreadMessage()
	dual.ID, dual.Text = "retryable", "first topic message"
	raw, err := json.Marshal(dual)
	if err != nil {
		t.Fatal(err)
	}
	receive := func() error {
		return s.ChannelReceive(t.Context(), ChannelReceive{Channel: "examplechat", Message: raw})
	}
	store.setFailPut(errors.New("synthetic alias write failure"))
	var retry *rpc.Error
	if err := receive(); !errors.As(err, &retry) || retry.Code != CodeTryAgain {
		t.Fatalf("failed alias write = %v, want try_again", err)
	}
	if _, found := store.rawSetting(channelsStateKey); found {
		t.Fatal("a failed alias write consumed the message ID")
	}
	for _, key := range []string{testLegacyThreadKey, testTopicThreadKey} {
		if ref := s.storedThreadBindingForTest("examplechat", key, true); ref.isThread() {
			t.Fatalf("failed write left a topic for %q", key)
		}
	}
	store.setFailPut(nil)
	if err := receive(); err != nil {
		t.Fatal(err)
	}
	waitAdmissionDrained(t, s)
	if session := boundThread(s, testLegacyThreadKey); session == "" || boundThread(s, testTopicThreadKey) != session || f.sent() != 1 {
		t.Fatalf("redelivery did not bind both keys to the same conversation: %q", session)
	}
}

func TestThreadBindingLookupPriorityAndExpiry(t *testing.T) {
	now := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	const missing = -1
	for _, tc := range []struct {
		name                     string
		v2                       bool
		legacyAge, structuredAge int // hours
		want                     string
	}{
		{"live primary wins over newer alias", true, 1, 2, "structured"},
		{"expired primary falls back", true, 1, 3, "legacy"},
		{"missing primary falls back", true, 1, missing, "legacy"},
		{"both expired", true, 3, 3, ""},
		{"legacy primary uses thirty days", false, 30 * 24, 1, "legacy"},
		{"expired legacy primary falls back", false, 30*24 + 1, 3, "structured"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			_, f, _ := threadsChannelService(t, true)
			store := &memStore{}
			stored := map[string]*channelThread{}
			if tc.legacyAge != missing {
				stored[testLegacyThreadKey] = &channelThread{MachineID: "server", SessionID: "legacy", At: now.Add(-time.Duration(tc.legacyAge) * time.Hour)}
			}
			if tc.structuredAge != missing {
				stored[testTopicThreadKey] = &channelThread{MachineID: "server", SessionID: "structured", At: now.Add(-time.Duration(tc.structuredAge) * time.Hour)}
			}
			if err := store.PutSetting(ctx, channelsKey, map[string]channelPrefs{"examplechat": {ThreadIdleHours: 2, Threads: stored}}); err != nil {
				t.Fatal(err)
			}
			s := newServiceOn(t, f, store)
			setIntegrationsV2(t, s, tc.v2)
			s.setChannelTestClock(func() time.Time { return now })
			ref, _, err := s.threadBindingForTest(ctx, "examplechat", testDualThreadMessage())
			if err != nil {
				t.Fatal(err)
			}
			var got string
			if binding := s.lookupThreadBinding(ref); binding != nil {
				got = binding.SessionID
			}
			if got != tc.want {
				t.Fatalf("binding = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestThreadBindingRefreshAndResetPersistence(t *testing.T) {
	ctx := context.Background()
	s, f, _ := threadsChannelService(t, true)
	store := s.store().(*memStore)
	clock := &fakeClock{now: time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)}
	s.setChannelTestClock(clock.Now)
	structured, _, err := s.threadBindingForTest(ctx, "examplechat", testDualThreadMessage())
	if err != nil {
		t.Fatal(err)
	}
	if !s.refreshThreadBinding(structured, "server", "first") {
		t.Fatal("current binding did not accept the first delivery")
	}
	legacy := s.storedThreadBindingForTest("examplechat", testLegacyThreadKey, false)
	if legacy.topic != structured.topic || legacy.generation != structured.generation {
		t.Fatalf("legacy and structured aliases identify different topics: %+v, %+v", legacy, structured)
	}
	if got := s.lookupThreadBinding(legacy); got == nil || got.SessionID != "first" {
		t.Fatalf("legacy alias did not resolve the first delivery: %+v", got)
	}

	clock.advance(time.Hour)
	now := clock.Now()
	if !s.refreshThreadBinding(legacy, "server", "second") {
		t.Fatal("current binding did not accept the second delivery")
	}
	var persisted map[string]channelPrefs
	if found, err := store.Setting(ctx, channelsKey, &persisted); !found || err != nil {
		t.Fatalf("read stored bindings: found=%t err=%v", found, err)
	}
	for _, key := range []string{testLegacyThreadKey, testTopicThreadKey} {
		if b := persisted["examplechat"].Threads[key]; b == nil || b.SessionID != "second" || !b.At.Equal(now) {
			t.Fatalf("stored binding %q = %+v, want second at %v", key, b, now)
		}
	}
	topics := persisted["examplechat"].ThreadTopics
	if len(topics.Entities) != 1 || len(topics.Aliases) != 2 {
		t.Fatalf("stored topic book = %+v, want one topic and two aliases", topics)
	}
	before, _ := store.rawSetting(channelsKey)
	failure := errors.New("synthetic binding write failure")
	store.setFailPut(failure)
	if err := s.resetThreadBinding(&legacy); !errors.Is(err, failure) || !errors.Is(err, errChannelSettingsWrite) {
		t.Fatalf("reset error = %v, want channel settings write failure", err)
	}
	if got, _ := store.rawSetting(channelsKey); !bytes.Equal(got, before) {
		t.Fatal("failed reset changed persisted channel preferences")
	}
	for _, key := range []string{testLegacyThreadKey, testTopicThreadKey} {
		if got := boundThread(s, key); got != "second" {
			t.Errorf("failed reset changed the in-memory binding for %q to %q", key, got)
		}
	}
	store.setFailPut(nil)
	if err := s.resetThreadBinding(&legacy); err != nil {
		t.Fatal(err)
	}
	if got := s.lookupThreadBinding(structured); got != nil {
		t.Fatalf("single-format reset left a live topic binding: %+v", got)
	}
	if s.refreshThreadBinding(structured, "server", "late") {
		t.Fatal("a late delivery restored a reset binding")
	}
	reloaded := newServiceOn(t, f, store)
	for _, key := range []string{testLegacyThreadKey, testTopicThreadKey} {
		ref := reloaded.storedThreadBindingForTest("examplechat", key, false)
		if ref.topic != legacy.topic || ref.generation != structured.generation+1 || reloaded.lookupThreadBinding(ref) != nil {
			t.Fatalf("after reload %q resolved to %+v, want the reset topic %+v", key, ref, legacy)
		}
	}
}

func TestThreadBindingFollowsDeliveryAfterRefreshFailure(t *testing.T) {
	ctx := context.Background()
	store := &failPutKeyStore{memStore: &memStore{}, key: channelsKey, err: errors.New("synthetic refresh write failure")}
	now := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	previous := now.Add(-time.Hour)
	if err := store.memStore.PutSetting(ctx, channelsKey, map[string]channelPrefs{"examplechat": {Threads: map[string]*channelThread{
		testLegacyThreadKey: {MachineID: "server", SessionID: "topic-session", At: previous},
		testTopicThreadKey:  {MachineID: "server", SessionID: "topic-session", At: previous},
	}}}); err != nil {
		t.Fatal(err)
	}
	s, f, hub := admissionChannelService(t, true, store)
	close(hub.release)
	registerAssistantSession(f, "topic-session")
	s.setChannelTestClock(func() time.Time { return now })

	m := testDualThreadMessage()
	m.ID, m.Text = "delivered", "Continue the topic"
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ChannelReceive(ctx, ChannelReceive{Channel: "examplechat", Message: raw}); err != nil {
		t.Fatal(err)
	}
	waitAdmissionDrained(t, s)
	if got := lastSendOnceSession(f); got != "topic-session" {
		t.Errorf("delivered to %q, want topic-session", got)
	}
	var state channelsState
	if found, err := store.Setting(ctx, channelsStateKey, &state); !found || err != nil || len(state.Runs) != 1 {
		t.Fatalf("delivered turn was not persisted for following: found=%t runs=%d err=%v", found, len(state.Runs), err)
	}
	if got := state.Runs[0].SessionID; got != "topic-session" {
		t.Fatalf("following conversation %q, want topic-session", got)
	}
	for _, key := range []string{testLegacyThreadKey, testTopicThreadKey} {
		ref := s.storedThreadBindingForTest("examplechat", key, false)
		if got := s.lookupThreadBinding(ref); got == nil || !got.At.Equal(previous) {
			t.Errorf("failed refresh changed the in-memory binding for %q: %+v", key, got)
		}
	}
}

func TestThreadBindingQueuedNewKeepsAdmittedRoute(t *testing.T) {
	s, _, hub := admissionChannelService(t, true, nil)
	var release sync.Once
	defer release.Do(func() { close(hub.release) })
	ctx := context.Background()
	receive := func(id, text string) {
		t.Helper()
		m := testDualThreadMessage()
		m.ID, m.Text, m.Thread = id, text, ""
		raw, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := s.ChannelReceive(ctx, ChannelReceive{Channel: "examplechat", Message: raw}); err != nil {
			t.Fatal(err)
		}
	}
	receive("first", "First topic message")
	select {
	case <-hub.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("first topic message never reached the node")
	}
	receive("reset", "/new Start this topic over")
	setIntegrationsV2ViaHTTP(t, s, false, true)
	release.Do(func() { close(hub.release) })
	waitAdmissionDrained(t, s)

	if got := boundThread(s, testTopicThreadKey); got != "s2" {
		t.Errorf("topic binding after the queued reset = %q, want the new topic conversation s2", got)
	}
	if rt, err := s.channelTarget(ctx, "examplechat"); err != nil || !rt.main {
		t.Errorf("queued topic reset changed the default target to %q: %v", rt.session.SessionID, err)
	}
}
