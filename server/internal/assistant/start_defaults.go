package assistant

import (
	"context"
	"errors"
	"math/rand/v2"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/nwverse/persona/server/internal/extension"
	"github.com/nwverse/persona/server/internal/prompts"
	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

// Start defaults: what a task's start runs with beyond what the caller named. A separate source supplies them at
// start time (the manager's extensions). Every field is opt-in: the zero value leaves the start exactly as the caller
// and the model configuration would have made it, and explicit caller values always win.

// StartDefaults is one source's answer for a task start.
type StartDefaults struct {
	// By says what supplied the answer, for the task's why and the logs; never an internal name.
	By string
	// Presets: the Agent presets (model profile tiers) to try, in order. The first one the task's computer can start
	// now wins; weights share starts among the usable ones. A preset that cannot be read, whose launcher the computer
	// lacks or whose launcher is at its usage limit is passed over. None: keep the start's own launcher and model.
	Presets []PresetChoice
	// Effort: applied when the caller gave none and the chosen preset has no effort of its own.
	Effort string
	// Ultracode: applied only to a Claude Code start whose caller did not say.
	Ultracode *bool
	// Goal: GoalFromDoneWhen turns the task's done_when into one checkable condition typed as /goal before the first
	// message (docs/integrations/agent-goal.md "A goal set at the start"). Only new Claude Code starts take it.
	Goal string
	// PlanFirst asks for the self-planning constraint in the task's prompt, for a launcher whose model does not act
	// on a thinking-effort setting (the prompt is then the only lever).
	PlanFirst bool
}

// GoalFromDoneWhen: build the start's goal from the task's done_when.
const GoalFromDoneWhen = "done_when"

// PresetChoice is one preset of StartDefaults: its name is a model profile tier, weight its share among usable ones.
type PresetChoice struct {
	Name   string
	Weight int
}

// StartFacts is what the source sees when asked for a task's defaults: only the kind of work, never the task's words.
type StartFacts struct {
	Machine, Role string
	Readonly      bool
	// Supervisor: main (the main conversation), leader (a leader conversation), conversation (another assistant
	// conversation) or background (the built-in background supervisor).
	Supervisor string
	// DoneWhen: how many acceptance criteria the task has (the criteria themselves stay in the task).
	DoneWhen int
}

const (
	supMain         = "main"
	supLeader       = "leader"
	supConversation = "conversation"
	supBackground   = "background"
)

// startDefaultsSource answers a task start's defaults; false or an error means no defaults, fail-closed: a source
// that does not answer never changes a start. The manager's extension layer sets it; tests stub it.
type startDefaultsSource interface {
	StartDefaults(ctx context.Context, facts StartFacts) (StartDefaults, bool, error)
}

// presetIntN is the roll over the usable presets' weights; a var so tests are deterministic.
var presetIntN = func(n int) int { return rand.IntN(n) }

// taskSupervisorKind classifies the conversation that follows up a task: what its supervision ledger names (written
// when the plan started, before the agent), else the leader conversation the plan handed it to. A task leader's
// worker is its leader's; a task no conversation follows up has no kind ("") and matches only rules that name none.
func (s *Service) taskSupervisorKind(ctx context.Context, t *PlanTask) string {
	if t.Parent != "" {
		return supLeader // a task leader's worker: its leader's agent follows it up
	}
	sup := t.Supervisor
	if t.TodoID != "" {
		if todo, ok := s.todoNow(t.TodoID); ok && todo.Supervision != nil && todo.Supervision.Supervisor != "" {
			sup = todo.Supervision.Supervisor
		}
	}
	switch {
	case sup == "":
		return ""
	case s.isLeader(ctx, sup):
		return supLeader
	case s.isBackground(ctx, sup):
		return supBackground
	case s.isMain(ctx, sup):
		return supMain
	}
	return supConversation
}

// matchStartDefaults finds the first rule of the first holding contribution whose when fits the task, and turns it
// into the start's defaults. Contributions keep the order the registry lists them; the extension's id is the source
// name for the task's why.
func (s *Service) matchStartDefaults(items []extension.StartDefaultsItem, facts StartFacts) (StartDefaults, bool) {
	for _, item := range items {
		for _, r := range item.Rules {
			if !startDefaultsMatch(r.When, facts) {
				continue
			}
			d := StartDefaults{By: item.ID, Effort: r.Then.Effort, Ultracode: r.Then.Ultracode, Goal: r.Then.Goal, PlanFirst: r.Then.PlanFirst}
			for _, p := range r.Then.Presets {
				d.Presets = append(d.Presets, PresetChoice{Name: p.Name, Weight: p.Weight})
			}
			return d, true
		}
	}
	return StartDefaults{}, false
}

// startDefaultsMatch says whether a rule's when fits a task's facts; every set field has to fit.
func startDefaultsMatch(m extension.StartDefaultMatch, f StartFacts) bool {
	if len(m.Supervisor) > 0 && !slices.Contains(m.Supervisor, f.Supervisor) {
		return false
	}
	if m.Role != "" && m.Role != f.Role {
		return false
	}
	if m.Readonly != nil && *m.Readonly != f.Readonly {
		return false
	}
	return true
}

// roleOf is the task's role as a fact: roleLeader for a task leader, else worker.
func roleOf(t *PlanTask) string {
	if t.Role == roleLeader {
		return roleLeader
	}
	return "worker"
}

// applyStartDefaults adds the source's defaults to a task and its resolved start params, after the launcher fit (which
// can fill and remove params) and before permission, the goal and session.start. caller is what the task named before
// any tier, Model Settings or the fit filled something in. Returned on the task's Why: what was applied and what was
// passed over. It mutates only this task and this start.
func (s *Service) applyStartDefaults(ctx context.Context, t *PlanTask, caller startPick, params map[string]any) {
	if s.startDefaultsFor == nil {
		return
	}
	d, ok, err := s.startDefaultsFor(ctx, StartFacts{
		Machine: t.Machine, Role: roleOf(t), Readonly: t.Readonly,
		Supervisor: s.taskSupervisorKind(ctx, t), DoneWhen: len(t.DoneWhen),
	})
	if err != nil {
		s.logf("task %s: start defaults: %v", t.TodoID, err)
		return
	}
	if !ok {
		return
	}
	var why []string
	agent, _ := params["launcher"].(string)
	isClaude := s.launcherAgent(ctx, t.Machine, agent) == "claude"
	explicitEffort := strings.TrimSpace(caller.effort) != ""
	explicitModel := strings.TrimSpace(caller.model) != ""
	explicitLauncher := strings.TrimSpace(caller.launcher) != ""
	explicitTier := strings.TrimSpace(caller.tier) != ""

	if len(d.Presets) > 0 && !explicitLauncher && !explicitModel && !explicitTier {
		if name, tier, note, found := s.chooseStartPreset(ctx, t, d.Presets); found {
			params["launcher"], params["model"] = tier.Launcher, tier.Model
			if tier.Effort != "" && !explicitEffort { // the caller's own effort still wins
				params["effort"] = tier.Effort
				t.Effort = tier.Effort
			}
			t.Launcher, t.Model, t.Tier = tier.Launcher, tier.Model, name
			why = append(why, note)
		} else if note != "" {
			why = append(why, note)
		}
	}
	// Effort, strongest first: the caller's own effort, then the effort of a tier the caller named; otherwise the
	// source's effort wins over Model Settings' default (the source is the policy for how this task starts). Claude
	// Code only; other agents keep whatever they were given.
	tierEffort := false
	if explicitTier {
		if tm, err := s.tierModel(ctx, caller.tier); err == nil && tm.Effort != "" {
			tierEffort = true
		}
	}
	if isClaude && strings.TrimSpace(d.Effort) != "" && !explicitEffort && !tierEffort {
		params["effort"] = d.Effort
		t.Effort = d.Effort
	}
	if isClaude && d.Ultracode != nil && t.Ultracode == nil {
		if *d.Ultracode {
			params["ultracode"] = true
			on := true
			t.Ultracode = &on
		}
	}
	if isClaude && d.Goal == GoalFromDoneWhen && len(t.DoneWhen) > 0 {
		if existing, _ := params["goal"].(string); strings.TrimSpace(existing) == "" {
			goal := prompts.RenderTaskGoal(t.DoneWhen)
			if utf8.RuneCountInString(goal) <= proto.StartGoalMax {
				params["goal"] = goal
			} else {
				s.logf("task %s: goal longer than %d runes, no /goal set", t.TodoID, proto.StartGoalMax)
			}
		}
	}
	if d.PlanFirst {
		t.planFirst = true
	}
	if d.By != "" && len(why) > 0 {
		t.noteWhy(d.By + ": " + strings.Join(why, "; "))
	}
}

// chooseStartPreset picks the first usable preset, or one of the weighted usable ones. A usable preset is one the
// profile in use has and whose launcher the task's computer starts now: present on the machine and not at its usage
// limit (a launch that failed for another reason still gets tried, the way an ordinary default does).
func (s *Service) chooseStartPreset(ctx context.Context, t *PlanTask, choices []PresetChoice) (name string, tier ModelTier, note string, found bool) {
	p, _, err := s.activeModelProfile(ctx)
	if p == nil || err != nil {
		return "", ModelTier{}, "", false
	}
	launchers, err := s.pickerLaunchers(ctx, t.Machine)
	known := err == nil
	usable, skipped := make([]PresetChoice, 0, len(choices)), make([]string, 0)
	for _, c := range choices {
		if c.Name == "" {
			continue
		}
		tier, ok := p.Tiers[c.Name]
		if !ok {
			skipped = append(skipped, c.Name+": unknown preset")
			continue
		}
		has := !known || slices.ContainsFunc(launchers, func(l ConfiguredLauncher) bool { return l.Name == tier.Launcher })
		if known && !has {
			skipped = append(skipped, c.Name+": this computer has no launcher "+tier.Launcher)
			continue
		}
		if _, bad := s.launcherUnusable(ctx, t.Machine, tier.Launcher); bad {
			skipped = append(skipped, c.Name+": "+tier.Launcher+" is not available yet")
			continue
		}
		usable = append(usable, PresetChoice{Name: c.Name, Weight: max(c.Weight, 1)})
	}
	if len(usable) == 0 {
		return "", ModelTier{}, strings.Join(skipped, "; "), false
	}
	weighted := func() PresetChoice {
		total := 0
		for _, c := range usable {
			total += c.Weight
		}
		roll := presetIntN(total)
		for _, c := range usable {
			if roll < c.Weight {
				return c
			}
			roll -= c.Weight
		}
		return usable[len(usable)-1]
	}
	pick := usable[0]
	if len(usable) > 1 && slices.ContainsFunc(usable, func(c PresetChoice) bool { return c.Weight != usable[0].Weight }) {
		pick = weighted()
	}
	note = "preset " + pick.Name
	if len(skipped) > 0 {
		note += " (passed over: " + strings.Join(skipped, "; ") + ")"
	}
	return pick.Name, p.Tiers[pick.Name], note, true
}

// goalRefusal: a session.start that carried a goal was refused for it (an old node, an agent other than Claude Code, a
// resume or a screenless host). A goal is an extra, so the caller starts the agent again without it and says why.
func (s *Service) goalRefusal(err error, params map[string]any) (reason string, refused bool) {
	if on, _ := params["goal"].(string); on == "" {
		return "", false
	}
	var re *rpc.Error
	if !errors.As(err, &re) || re.Code != proto.CodeStartGoalUnsupported {
		return "", false
	}
	return re.Message, true
}
