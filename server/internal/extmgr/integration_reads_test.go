package extmgr

import (
	"context"
	"errors"
	"net/http"
	"testing"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/botport"
	"github.com/nwverse/persona/server/internal/mailwire"
	"github.com/nwverse/persona/server/internal/moduleswitch"
	"github.com/nwverse/persona/server/internal/subscriptions"
)

type integrationMail struct {
	Mail
	err error
}

func (m integrationMail) ListAccounts() ([]mailwire.MailAccount, error) { return nil, m.err }

func TestIntegrationServiceReadsFollowProviderSwitches(t *testing.T) {
	if !botport.InBuild() {
		t.Skip("Lark is outside this build")
	}
	needsMailModule(t)
	for _, tt := range []struct {
		name     string
		off      []string
		failMail bool
		wantLark assistantwire.IntegrationState
		wantMail assistantwire.IntegrationState
	}{
		{"available providers need setup", nil, false, "needs_setup", "needs_setup"},
		{"mail read failed", nil, true, "needs_setup", "unavailable"},
		{"Lark switched off", []string{"lark"}, false, "module_off", "needs_setup"},
		{"mail switched off", []string{"mail"}, true, "needs_setup", "module_off"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			h := New(Options{Dir: t.TempDir(), Modules: moduleswitch.Off(tt.off...)})
			h.bots.loaded = true
			mail := integrationMail{}
			if tt.failMail {
				mail.err = errors.New("provider diagnostic")
			}
			h.mail = mail
			close(h.started)
			services := h.IntegrationServices(t.Context())
			if len(services) != 2 || services[0].ModuleFeature != "module.lark" || services[0].Availability.State != tt.wantLark ||
				services[1].ModuleFeature != "module.mail" || services[1].Availability.State != tt.wantMail {
				t.Fatalf("services %+v", services)
			}
			if tt.wantMail == assistantwire.IntegrationStateUnavailable &&
				services[1].Availability.Reason != assistantwire.IntegrationReasonReadFailed {
				t.Fatalf("mail failure %+v", services[1])
			}
		})
	}
}

func TestIntegrationFeishuFileCredentialsKeepBotControls(t *testing.T) {
	needsLarkModule(t)
	h := newTestManager(t)
	store := newBotStore()
	h.store = store
	api := newFakeOpenAPI(t)
	h.cfg.Bots.Feishu = FeishuBotConfig{AppID: "cli_fixture", AppSecret: "fixture-secret", Domain: api.srv.URL}
	ctx, cancel := context.WithCancel(t.Context())
	t.Cleanup(cancel)
	h.startBots(ctx)
	mux := http.NewServeMux()
	h.botRoutes(mux)
	w := &botWorld{h: h, st: store, api: api, a: withAssistant(h), mux: mux, ctx: ctx}
	check := func(wantState assistantwire.IntegrationState) {
		t.Helper()
		bot := h.IntegrationServices(ctx)[0]
		if bot.ID != "feishu-bot" || bot.Availability.State != wantState || !bot.CredentialsReadOnly {
			t.Fatalf("expected file-managed credentials and bot state %s: %+v", wantState, bot)
		}
	}
	check(assistantwire.IntegrationStateNeedsSetup)
	const path = "/api/v1/bots/feishu"
	if status, out := w.do(t, http.MethodPut, path, `{"enabled":true}`); status != http.StatusOK ||
		out["enabled"] != true || out["from_config"] != true {
		t.Fatalf("enable file-configured bot: %d %v", status, out)
	}
	w.botStarted(t)
	w.pair(t)
	if status, out := w.do(t, http.MethodPut, path, `{"enabled":false}`); status != http.StatusOK ||
		out["enabled"] != false || out["owner"] == nil || out["from_config"] != true {
		t.Fatalf("disable paired bot: %d %v", status, out)
	}
	check(assistantwire.IntegrationStateDisabled)
	if status, out := w.do(t, http.MethodDelete, path+"/owner", ""); status != http.StatusOK ||
		out["owner"] != nil || out["from_config"] != true {
		t.Fatalf("unbind file-configured bot: %d %v", status, out)
	}
	check(assistantwire.IntegrationStateNeedsSetup)
}

func TestIntegrationSubscriptionsUseEffectiveAlertState(t *testing.T) {
	h := newTestManager(t)
	store := subscriptions.NewMemStore()
	h.subs = subscriptions.New(h.subscriptionOptions(store))
	cases := []struct {
		id, runState string
		on           bool
		want         assistantwire.IntegrationSubscriptionAlertState
	}{
		{"failing", subscriptions.StateFailing, true, assistantwire.IntegrationSubscriptionAlertStateFailing},
		{"recipe", subscriptions.StateNeedsRecipe, true, assistantwire.IntegrationSubscriptionAlertStateNeedsRecipe},
		{"limited", subscriptions.StateRateLimited, true, assistantwire.IntegrationSubscriptionAlertStateRateLimited},
		{"disconnected", subscriptions.StateNotConnected, true, assistantwire.IntegrationSubscriptionAlertStateNotConnected},
		{"off", subscriptions.StateFailing, false, ""},
		{"healthy", subscriptions.StateOK, true, ""},
		{"future", "future_state", true, ""},
	}
	for _, tc := range cases {
		sub := subscriptions.Subscription{ID: tc.id, RunState: tc.runState, ToInbox: tc.on,
			Label: "Example source", Error: &subscriptions.Problem{Message: "  The source could not be read.  "}}
		if err := store.CreateSubscription(t.Context(), sub); err != nil {
			t.Fatal(err)
		}
	}
	got, err := h.IntegrationSubscriptions(t.Context())
	if err != nil || len(got) != len(cases) {
		t.Fatalf("subscription reads %+v: %v", got, err)
	}
	byID := make(map[string]assistantwire.IntegrationSubscriptionRead, len(got))
	for _, sub := range got {
		byID[string(sub.ID)] = sub
	}
	for _, tc := range cases {
		wantMessage := ""
		if tc.want != "" {
			wantMessage = "The source could not be read."
		}
		sub, ok := byID[tc.id]
		if !ok || sub.AlertState != tc.want || sub.AlertMessage != wantMessage {
			t.Errorf("%s: got %q and %q, want %q and %q", tc.id, sub.AlertState, sub.AlertMessage, tc.want, wantMessage)
		}
	}
}
