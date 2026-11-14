package assistant

import (
	"context"
	"encoding/json"
	"maps"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/prompts"
	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

// stubStartDefaults makes s ask fn for every task start's defaults; seen are the facts each start gave, in order.
func stubStartDefaults(s *Service, fn func(StartFacts) (StartDefaults, bool)) (seen func() []StartFacts) {
	var mu sync.Mutex
	var facts []StartFacts
	s.startDefaultsFor = func(_ context.Context, f StartFacts) (StartDefaults, bool, error) {
		mu.Lock()
		facts = append(facts, f)
		mu.Unlock()
		d, ok := fn(f)
		return d, ok, nil
	}
	return func() []StartFacts {
		mu.Lock()
		defer mu.Unlock()
		return slices.Clone(facts)
	}
}

// saveTiers makes a model profile with these tiers in use.
func saveTiers(t *testing.T, s *Service, tiers map[string]ModelTier) {
	t.Helper()
	ctx := context.Background()
	ps := modelProfiles{Revision: 1, Profiles: []ModelProfile{{ID: "p", Name: "P", Tiers: tiers}}}
	if err := s.store().PutSetting(ctx, modelProfilesKey, ps); err != nil {
		t.Fatal(err)
	}
	if err := s.store().PutSetting(ctx, modelProfileStateKey, modelProfileState{Active: "p"}); err != nil {
		t.Fatal(err)
	}
}

// fullDefaults asks for everything: ultracode, effort max, a goal from done_when, and these presets in order.
func fullDefaults(presets ...string) StartDefaults {
	on := true
	d := StartDefaults{Ultracode: &on, Effort: "max", Goal: GoalFromDoneWhen, By: "Example"}
	for _, p := range presets {
		d.Presets = append(d.Presets, PresetChoice{Name: p})
	}
	return d
}

// defaultsTask is a writable task on box that names nothing it runs on, with two criteria.
func defaultsTask() PlanTask {
	return PlanTask{Title: "Fix the build", Prompt: "fix the build", Machine: "box", Cwd: "/Users/me/code/app",
		Permission: "bypassPermissions", DoneWhen: []string{"CI is green", "the fix is merged"}}
}

// defaultsHub is launcherFitHub (box has the launcher gateway, l4 has other; Model Settings › Agents runs gateway's
// gw-model at high) with three presets in use.
func defaultsHub(t *testing.T) (*Service, *fakeHub) {
	t.Helper()
	s, f := launcherFitHub(t)
	saveTiers(t, s, map[string]ModelTier{
		"deep":  {Launcher: "gateway", Model: "gw-deep", Effort: "high"},
		"quick": {Launcher: "gateway", Model: "gw-quick"},
		"plain": {Launcher: "claude", Model: "claude-plain"},
	})
	return s, f
}

// The defaults fill in what the task says nothing about: the first usable preset (its effort overridden), ultracode,
// effort max and a goal from done_when; the card's task records what ran and why.
func TestStartDefaultsFillWhatTheTaskLeavesOpen(t *testing.T) {
	s, f := defaultsHub(t)
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults("deep", "quick"), true })
	task := defaultsTask()
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	st := startsOf(f)[0]
	if st["launcher"] != "gateway" || st["model"] != "gw-deep" || st["effort"] != "max" || st["ultracode"] != true {
		t.Fatalf("start = %v", st)
	}
	if want := prompts.RenderTaskGoal(task.DoneWhen); st["goal"] != want || !strings.Contains(want, "CI is green") ||
		strings.Contains(want, "\n") {
		t.Fatalf("goal = %q, want %q", st["goal"], want)
	}
	if !strings.Contains(st["prompt"].(string), "fix the build") {
		t.Fatalf("the prompt went somewhere else: %v", st["prompt"])
	}
	if task.Ultracode == nil || !*task.Ultracode || task.Effort != "max" || task.Model != "gw-deep" || !strings.Contains(task.Why, "Example") {
		t.Fatalf("task = %+v", task)
	}
}

// Whatever the caller named itself wins: an effort, ultracode off, a tier, a model or a launcher.
func TestStartDefaultsYieldToTheCaller(t *testing.T) {
	off := false
	for _, c := range []struct {
		name string
		edit func(*PlanTask)
		want map[string]any // values the start must carry; nil: the key must be unset
	}{
		{"effort", func(p *PlanTask) { p.Effort = "low" },
			map[string]any{"effort": "low", "model": "gw-deep", "ultracode": true}},
		{"tier without one keeps the source's effort", func(p *PlanTask) { p.Tier = "quick" },
			map[string]any{"effort": "max", "model": "gw-quick", "ultracode": true}},
		{"ultracode off", func(p *PlanTask) { p.Ultracode = &off },
			map[string]any{"effort": "max", "model": "gw-deep", "ultracode": nil}},
		{"tier with an effort", func(p *PlanTask) { p.Tier = "deep" },
			map[string]any{"effort": "high", "model": "gw-deep", "ultracode": true}},

		{"model", func(p *PlanTask) { p.Model = "gw-own" },
			map[string]any{"launcher": "gateway", "effort": "max", "model": "gw-own", "ultracode": true}},
		{"launcher", func(p *PlanTask) { p.Launcher = "claude" },
			map[string]any{"launcher": "claude", "effort": "max", "model": nil, "ultracode": true}},
	} {
		t.Run(c.name, func(t *testing.T) {
			s, f := defaultsHub(t)
			stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults("deep"), true })
			task := defaultsTask()
			c.edit(&task)
			if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
				t.Fatal(err)
			}
			st := startsOf(f)[0]
			for k, v := range c.want {
				if v == nil && !unset(st[k]) && st[k] != false || v != nil && st[k] != v {
					t.Fatalf("%s = %v, want %v (start %v)", k, st[k], v, st)
				}
			}
		})
	}
}

// No defaults, the source saying none or none at all: the start is exactly what it was before defaults existed.
func TestStartDefaultsNoneLeaveTheStartAsItWas(t *testing.T) {
	run := func(stub bool) map[string]any {
		s, f := defaultsHub(t)
		if stub {
			stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return StartDefaults{}, false })
		}
		task := defaultsTask()
		if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
			t.Fatal(err)
		}
		st := maps.Clone(startsOf(f)[0])
		delete(st, "session_id") // a new id each start
		return st
	}
	before, after := run(false), run(true)
	if !reflect.DeepEqual(before, after) {
		t.Fatalf("without defaults:\n%v\nwith none asked:\n%v", before, after)
	}
	for _, k := range []string{"goal", "ultracode"} {
		if _, ok := after[k]; ok {
			t.Fatalf("%s sent without defaults: %v", k, after)
		}
	}
}

// A preset that cannot run on the task's computer now is passed over for the next: not in the profile, a launcher the
// computer lacks, a launcher at its usage limit there.
func TestStartDefaultsPassOverPresetsThatCannotRun(t *testing.T) {
	s, f := defaultsHub(t)
	ctx := context.Background()
	saveTiers(t, s, map[string]ModelTier{
		"elsewhere": {Launcher: "other", Model: "o-1"}, // l4's, not box's
		"limited":   {Launcher: "gateway", Model: "gw-deep"},
		"plain":     {Launcher: "claude", Model: "claude-plain"},
	})
	resets := time.Now().Add(2 * time.Hour)
	s.recordHealth(ctx, "box", "gateway", healthResult{err: "You've hit your usage limit", source: healthSourceSession,
		kind: errKindUsageLimit, resetsAt: &resets})
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) {
		return fullDefaults("gone", "elsewhere", "limited", "plain"), true
	})
	task := defaultsTask()
	task.MachinePolicy = machineFlexible // a fixed task would fail on the limited launcher instead of passing over
	if _, _, err := s.startTaskAgent(ctx, &task, false); err != nil {
		t.Fatal(err)
	}
	if st := startsOf(f)[0]; st["launcher"] != "claude" || st["model"] != "claude-plain" {
		t.Fatalf("start = %v", st)
	}
	for _, name := range []string{"gone", "elsewhere", "limited"} {
		if !strings.Contains(task.Why, name) {
			t.Fatalf("why does not say %s was passed over: %q", name, task.Why)
		}
	}
	// None usable: the task starts as it would have with no preset, and says why.
	s, f = defaultsHub(t)
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults("gone"), true })
	task = defaultsTask()
	if _, _, err := s.startTaskAgent(ctx, &task, false); err != nil {
		t.Fatal(err)
	}
	st := startsOf(f)[0]
	if st["launcher"] != "gateway" || st["model"] != "gw-model" {
		t.Fatalf("no usable preset: %v", st)
	}
	if st["effort"] != "max" { // no caller effort: the source's effort still wins over Model Settings
		t.Fatalf("effort = %v, want the source's max", st["effort"])
	}
}

// Weights share starts among the usable presets in proportion; without weights the first usable one always wins.
func TestStartDefaultsWeights(t *testing.T) {
	ctx := context.Background()
	pick := func(roll int, choices ...PresetChoice) any {
		s, f := defaultsHub(t)
		old := presetIntN
		presetIntN = func(n int) int {
			if n != 4 {
				t.Fatalf("rolled over %d, want the weights' sum 4", n)
			}
			return roll
		}
		t.Cleanup(func() { presetIntN = old })
		d := fullDefaults()
		d.Presets = choices
		stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return d, true })
		task := defaultsTask()
		if _, _, err := s.startTaskAgent(ctx, &task, false); err != nil {
			t.Fatal(err)
		}
		return startsOf(f)[0]["model"]
	}
	weighted := []PresetChoice{{Name: "deep", Weight: 1}, {Name: "gone", Weight: 5}, {Name: "quick", Weight: 3}}
	if got := pick(0, weighted...); got != "gw-deep" {
		t.Fatalf("roll 0: %v", got)
	}
	if got := pick(1, weighted...); got != "gw-quick" {
		t.Fatalf("roll 1: %v", got)
	}
	if got := pick(3, weighted...); got != "gw-quick" {
		t.Fatalf("roll 3: %v", got)
	}
}

// A goal goes only with criteria and only to Claude Code; ultracode and effort max only to Claude Code too.
func TestStartDefaultsOnlyWhatTheAgentTakes(t *testing.T) {
	s, f := defaultsHub(t)
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults(), true })
	task := defaultsTask()
	task.DoneWhen = nil
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	if st := startsOf(f)[0]; st["goal"] != nil || st["ultracode"] != true {
		t.Fatalf("no criteria: %v", st)
	}

	s, f = defaultsHub(t)
	f.zshrc["box"] = append(f.zshrc["box"], proto.Launcher{Name: "cx", Source: "zshrc", Agent: "codex"})
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults(), true })
	task = defaultsTask()
	task.Launcher = "cx"
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	if st := startsOf(f)[0]; st["goal"] != nil || st["ultracode"] != nil || st["effort"] == "max" {
		t.Fatalf("codex: %v", st)
	}
	if st := startsOf(f)[0]; st["launcher"] != "cx" {
		t.Fatalf("the codex launcher was changed: %v", st)
	}
}

// A source can ask for the self-planning constraint in the prompt: for launches whose model does not act on effort.
func TestStartDefaultsPlanFirstGoesInThePrompt(t *testing.T) {
	s, f := defaultsHub(t)
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) {
		d := fullDefaults()
		d.PlanFirst = true
		return d, true
	})
	task := defaultsTask()
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	if prompt, _ := startsOf(f)[0]["prompt"].(string); !strings.Contains(prompt, prompts.RenderPlanFirstTaskConstraint()) {
		t.Fatalf("prompt = %q", prompt)
	}
}

// A computer whose node cannot set a goal before the first message starts the agent without one, and the task says
// why; with ultracode refused too, each goes in turn.
func TestStartDefaultsGoalRefusedStartsWithoutIt(t *testing.T) {
	s, f := defaultsHub(t)
	f.refuseGoal = &rpc.Error{Code: proto.CodeStartGoalUnsupported, Message: "the node is too old"}
	f.refuseUltra = &rpc.Error{Code: "ultracode_unsupported", Message: "no ultracode here"}
	stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return fullDefaults(), true })
	task := defaultsTask()
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	// The node refuses ultracode first, then the goal: three tries, each without what the one before was refused.
	starts := startsOf(f)
	if len(starts) != 3 || starts[0]["goal"] == nil || starts[0]["ultracode"] != true ||
		starts[1]["goal"] == nil || starts[1]["ultracode"] != nil ||
		starts[2]["goal"] != nil || starts[2]["ultracode"] != nil {
		t.Fatalf("starts = %v", starts)
	}
	if !strings.Contains(task.Why, "the node is too old") || !strings.Contains(task.Why, "no ultracode here") {
		t.Fatalf("why = %q", task.Why)
	}
}

// The facts a start gives say who follows the task up, so a source can tell a leader's worker from the main
// conversation's own task; read-only and the task's role ride along.
func TestStartDefaultsFactsSayWhoSupervises(t *testing.T) {
	s, f := newQuietService(t)
	f.devices = []Device{{ID: "box", Name: "box", Home: "/home/me", Online: true, Reachable: true}}
	f.known["lead1"] = true
	f.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
		proto.Session{SessionID: "lead1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
	setSettings(t, s, func(v *Settings) { v.Leaders = []string{"lead1"} })
	seen := stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) { return StartDefaults{}, false })
	ctx := context.Background()
	plan := func(conversation, supervisor string, task map[string]any) {
		t.Helper()
		task["machine"], task["cwd"], task["why"] = "box", "/code/app", "the repo is on box"
		args := map[string]any{"tasks": []map[string]any{task}}
		if supervisor != "" {
			args["supervisor"] = supervisor // the whole plan goes to a leader conversation
		}
		raw, _ := json.Marshal(args)
		res, err := s.toolTaskPlan(ctx, conversation, raw)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(res, "nothing has started") {
			id := planID(t, res)
			if id == "" {
				return // an auto-queued task dispatches itself
			}
			if _, err := s.startPlan(ctx, id, nil, false); err != nil {
				t.Fatal(err)
			}
		}
	}
	planWait := func(want int) {
		waitFor(t, "a task's defaults", func() bool { return len(seen()) == want })
	}
	plan("lead1", "", map[string]any{"title": "A", "prompt": "fix it", "readonly": false, "done_when": []string{"CI is green"}, "expect_minutes": 30})
	planWait(1)
	plan("conv1", "", map[string]any{"title": "B", "prompt": "look it up", "readonly": true, "goal": "the answer", "expect_minutes": 10})
	planWait(2)
	plan("conv1", "lead1", map[string]any{"title": "C", "prompt": "fix that", "readonly": false, "done_when": []string{"CI is green"},
		"expect_minutes": 30})
	planWait(3)
	got := seen()
	if a := got[0]; a.Supervisor != dispatchClassLeader || a.Readonly || a.Role != "worker" || a.DoneWhen != 1 || a.Machine != "box" {
		t.Fatalf("leader's task: %+v", a)
	}
	if b := got[1]; b.Supervisor != dispatchClassMain || !b.Readonly {
		t.Fatalf("main's own task: %+v", b)
	}
	if c := got[2]; c.Supervisor != dispatchClassLeader {
		t.Fatalf("task handed to the leader: %+v", c)
	}
}

// putStartDefaults sends PUT /api/v1/assistant/start-defaults as role (full, extension, read-only) and answers the code
// and body.
func putStartDefaults(t *testing.T, s *Service, role, ext, body string) (int, string) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/assistant/start-defaults", strings.NewReader(body))
	if role != "" {
		req.Header.Set(RoleHeader, role)
	}
	if ext != "" {
		req.Header.Set(ExtensionHeader, ext)
	}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, req)
	return rec.Code, rec.Body.String()
}

// A policy whose rule picks the tasks a leader conversation follows up: those start with the defaults, the main
// conversation's own task starts exactly as it would without the policy, and a read-only task is left alone too. The
// policy is the one an extension wrote through the route.
func TestStartDefaultsPolicyOnlyWhereItsRuleMatches(t *testing.T) {
	s, f := newQuietService(t)
	f.devices = []Device{{ID: "box", Name: "box", Home: "/home/me", Online: true, Reachable: true}}
	f.known["lead1"] = true
	f.setSessions("server", proto.Session{SessionID: "conv1", MachineID: "server", Assistant: true, Status: proto.StatusIdle},
		proto.Session{SessionID: "lead1", MachineID: "server", Assistant: true, Status: proto.StatusIdle})
	setSettings(t, s, func(v *Settings) { v.Leaders = []string{"lead1"} })
	expires := time.Now().Add(30 * time.Minute).UTC().Format(time.RFC3339)
	code, body := putStartDefaults(t, s, roleExtension, "kit", `{"start_defaults":{"expires_at":"`+expires+`","rules":[
		{"when":{"supervisor":["leader"],"readonly":false},"then":{"effort":"max","ultracode":true,"goal":"done_when"}}]}}`)
	if code != http.StatusOK {
		t.Fatalf("put: %d %s", code, body)
	}
	ctx := context.Background()
	start := func(conversation string, readonly bool) map[string]any {
		t.Helper()
		before := len(startsOf(f))
		task := map[string]any{"title": "Fix", "prompt": "fix it", "machine": "box", "cwd": "/code/app", "why": "the repo is on box",
			"readonly": readonly, "expect_minutes": 30}
		if readonly {
			task["goal"] = "the answer"
		} else {
			task["done_when"] = []string{"CI is green"}
		}
		raw, _ := json.Marshal(map[string]any{"tasks": []map[string]any{task}})
		res, err := s.toolTaskPlan(ctx, conversation, raw)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(res, "nothing has started") {
			if _, err := s.startPlan(ctx, planID(t, res), nil, false); err != nil {
				t.Fatal(err)
			}
		}
		waitFor(t, "the start", func() bool { return len(startsOf(f)) == before+1 })
		return startsOf(f)[before]
	}
	led := start("lead1", false)
	if led["ultracode"] != true || led["effort"] != "max" || led["goal"] == nil {
		t.Fatalf("a leader's worker started without the defaults: %v", led)
	}
	own := start("conv1", false)
	if own["ultracode"] != nil || own["goal"] != nil || own["effort"] == "max" {
		t.Fatalf("the main conversation's own task got the defaults: %v", own)
	}
	ro := start("lead1", true)
	if ro["ultracode"] != nil || ro["goal"] != nil || ro["effort"] == "max" {
		t.Fatalf("a read-only task got the defaults: %v", ro)
	}
}

// The route's rules: an extension's policy must expire within an hour; the owner may keep one without expiry; a
// read-only device is refused; an unknown field, class or effort is a 400; empty rules clear it; once expired it is
// gone from GET and from task starts.
func TestStartDefaultsRoute(t *testing.T) {
	s, f := defaultsHub(t)
	soon := time.Now().Add(30 * time.Minute).UTC().Format(time.RFC3339)
	late := time.Now().Add(3 * time.Hour).UTC().Format(time.RFC3339)
	rule := `"rules":[{"when":{},"then":{"effort":"max"}}]`
	for _, c := range []struct {
		name, role, body string
		code             int
	}{
		{"extension without expiry", roleExtension, `{"start_defaults":{` + rule + `}}`, http.StatusBadRequest},
		{"extension too far ahead", roleExtension, `{"start_defaults":{"expires_at":"` + late + `",` + rule + `}}`, http.StatusBadRequest},
		{"read-only device", "read", `{"start_defaults":{` + rule + `}}`, http.StatusForbidden},
		{"unknown field", roleFull, `{"start_defaults":{"rules":[{"when":{},"then":{"efort":"max"}}]}}`, http.StatusBadRequest},
		{"unknown class", roleFull, `{"start_defaults":{"rules":[{"when":{"supervisor":["owner"]},"then":{}}]}}`, http.StatusBadRequest},
		{"unknown effort", roleFull, `{"start_defaults":{"rules":[{"when":{},"then":{"effort":"banana"}}]}}`, http.StatusBadRequest},
		{"no body key", roleFull, `{}`, http.StatusBadRequest},
		{"owner without expiry", roleFull, `{"start_defaults":{` + rule + `}}`, http.StatusOK},
		{"extension within the hour", roleExtension, `{"start_defaults":{"expires_at":"` + soon + `",` + rule + `}}`, http.StatusOK},
	} {
		if code, body := putStartDefaults(t, s, c.role, "kit", c.body); code != c.code {
			t.Errorf("%s: %d %s", c.name, code, body)
		}
	}
	if p := s.activeStartDefaults(context.Background()); p == nil || p.By != "kit" {
		t.Fatalf("the extension's policy is not the one in force: %+v", p)
	}
	// The policy in force reaches a task start.
	task := defaultsTask()
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	if st := startsOf(f)[0]; st["effort"] != "max" {
		t.Fatalf("the policy did not reach the start: %v", st)
	}
	// Expired: gone from GET and from starts.
	past := time.Now().Add(-time.Minute)
	setSettings(t, s, func(v *Settings) { v.StartDefaults.ExpiresAt = &past })
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/assistant/start-defaults", nil))
	if got := strings.TrimSpace(rec.Body.String()); got != `{"start_defaults":{"rules":[]}}` {
		t.Fatalf("expired policy still listed: %s", got)
	}
	task = defaultsTask()
	if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
		t.Fatal(err)
	}
	if st := startsOf(f)[1]; st["effort"] == "max" {
		t.Fatalf("an expired policy still applied: %v", st)
	}
	// Empty rules clear it.
	if code, body := putStartDefaults(t, s, roleExtension, "kit", `{"start_defaults":{"rules":[]}}`); code != http.StatusOK {
		t.Fatalf("clear: %d %s", code, body)
	}
	if s.settings(context.Background()).StartDefaults != nil {
		t.Fatal("not cleared")
	}
}

// A preset the policy picks starts exactly as if the task had named that tier: the same launcher, model, launch
// fingerprint and permission, here for a preset whose launcher is not Model Settings' default.
func TestStartDefaultsPresetStartsAsIfTheTaskNamedIt(t *testing.T) {
	start := func(policy bool) map[string]any {
		t.Helper()
		s, f := defaultsHub(t)
		task := defaultsTask()
		if policy {
			stubStartDefaults(s, func(StartFacts) (StartDefaults, bool) {
				return StartDefaults{Presets: []PresetChoice{{Name: "plain"}}}, true
			})
		} else {
			task.Tier = "plain"
		}
		if _, _, err := s.startTaskAgent(context.Background(), &task, false); err != nil {
			t.Fatal(err)
		}
		if task.Tier != "plain" {
			t.Fatalf("the task does not name the preset it runs: %+v", task)
		}
		st := maps.Clone(startsOf(f)[0])
		delete(st, "session_id")
		return st
	}
	picked, named := start(true), start(false)
	if !reflect.DeepEqual(picked, named) {
		t.Fatalf("picked by the policy:\n%v\nnamed by the task:\n%v", picked, named)
	}
	if picked["launcher"] != "claude" || picked["model"] != "claude-plain" {
		t.Fatalf("not the preset's launcher and model: %v", picked)
	}
}
