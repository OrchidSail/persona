package extension

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/nwverse/persona/server/internal/proto"
)

// Start defaults for task agents (an extension point): an extension contributes the defaults a task start gets beyond
// what its caller named — an Agent preset, thinking effort, ultracode, a goal from the task's criteria, or a
// self-planning prompt. The manager reads the contributions of the extensions that are on and matches each task
// against their rules; nothing here starts anything itself. It is declarative: no process, no permission beyond the
// install's.

// StartDefaultSupervisors are the kinds of conversation a rule can match. They mirror the manager's own kinds, so an
// extension never sees a conversation id.
var StartDefaultSupervisors = []string{"main", "leader", "conversation", "background"}

// StartDefaultRoles are the task roles a rule can match.
var StartDefaultRoles = []string{"worker", "leader"}

// StartDefaultGoals are the goal sources a rule's then can name.
var StartDefaultGoals = []string{"done_when"}

// startDefaultMost bounds one contribution: rules and the presets and supervisors each rule names.
const startDefaultMost = 16

// StartDefaultMatch selects the tasks a rule applies to. Every set field has to fit; an empty one matches anything.
type StartDefaultMatch struct {
	// Supervisor: the conversation kind that follows the task up.
	Supervisor []string `json:"supervisor,omitempty"`
	// Role: the task's role.
	Role string `json:"role,omitempty"`
	// Readonly: true matches only read-only tasks, false only writable ones; omitted matches both.
	Readonly *bool `json:"readonly,omitempty"`
}

// StartDefaultPreset is an Agent preset (a model profile tier) to try, with its share among usable presets.
type StartDefaultPreset struct {
	Name   string `json:"name"`
	Weight int    `json:"weight,omitempty"`
}

// StartDefaultThen is the defaults a matching task gets. Every field is opt-in; an empty one changes nothing.
type StartDefaultThen struct {
	Presets   []StartDefaultPreset `json:"presets,omitempty"`
	Effort    string               `json:"effort,omitempty"`
	Ultracode *bool                `json:"ultracode,omitempty"`
	// Goal: "done_when" turns the task's acceptance criteria into one checkable goal set before its first message.
	Goal string `json:"goal,omitempty"`
	// PlanFirst asks for a self-planning constraint in the task's prompt, for launches whose model ignores effort.
	PlanFirst bool `json:"plan_first,omitempty"`
}

// StartDefaultRule is one match and its defaults; the first rule of the first contribution that matches wins.
type StartDefaultRule struct {
	When StartDefaultMatch `json:"when"`
	Then StartDefaultThen  `json:"then"`
}

// StartDefaultsItem is a contribution to start_defaults.
type StartDefaultsItem struct {
	ID string `json:"id"`
	// When: a boolean field of the extension's configuration; the whole contribution holds only while it is true, and
	// that field is its only switch (an agent_filters rule works the same way). Empty: it holds while the extension is on.
	When  string             `json:"when,omitempty"`
	Rules []StartDefaultRule `json:"rules"`
}

// Holds says whether the contribution takes effect under the extension's effective configuration.
func (s StartDefaultsItem) Holds(config json.RawMessage) bool {
	if s.When == "" {
		return true
	}
	var c map[string]any
	if json.Unmarshal(config, &c) != nil {
		return false
	}
	on, _ := c[s.When].(bool)
	return on
}

func init() {
	Points["start_defaults"] = Point{Name: "start_defaults", Namespace: "start_defaults", Version: 1, Item: func(raw json.RawMessage) (string, error) {
		var s StartDefaultsItem
		if err := strict(raw, &s); err != nil {
			return "", err
		}
		if !idPattern.MatchString(s.ID) {
			return s.ID, fmt.Errorf("start defaults id %q: a-z0-9_-", s.ID)
		}
		if s.When != "" && !configField.MatchString(s.When) {
			return s.ID, fmt.Errorf("start defaults %s: when %q: a configuration field's name", s.ID, s.When)
		}
		if len(s.Rules) == 0 {
			return s.ID, fmt.Errorf("start defaults %s: needs at least one rule", s.ID)
		}
		if len(s.Rules) > startDefaultMost {
			return s.ID, fmt.Errorf("start defaults %s: at most %d rules", s.ID, startDefaultMost)
		}
		for i, r := range s.Rules {
			at := fmt.Sprintf("start defaults %s: rule %d", s.ID, i+1)
			for _, kind := range r.When.Supervisor {
				if !slices.Contains(StartDefaultSupervisors, kind) {
					return s.ID, fmt.Errorf("%s: supervisor %q: only %s", at, kind, strings.Join(StartDefaultSupervisors, ", "))
				}
			}
			if len(r.When.Supervisor) > startDefaultMost {
				return s.ID, fmt.Errorf("%s: at most %d supervisors", at, startDefaultMost)
			}
			if r.When.Role != "" && !slices.Contains(StartDefaultRoles, r.When.Role) {
				return s.ID, fmt.Errorf("%s: role %q: only %s", at, r.When.Role, strings.Join(StartDefaultRoles, ", "))
			}
			if r.Then.Effort != "" && !slices.Contains(proto.EffortLevels, r.Then.Effort) {
				return s.ID, fmt.Errorf("%s: effort %q: one of %s", at, r.Then.Effort, strings.Join(proto.EffortLevels, ", "))
			}
			if r.Then.Goal != "" && !slices.Contains(StartDefaultGoals, r.Then.Goal) {
				return s.ID, fmt.Errorf("%s: goal %q: only %s", at, r.Then.Goal, strings.Join(StartDefaultGoals, ", "))
			}
			if len(r.Then.Presets) > startDefaultMost {
				return s.ID, fmt.Errorf("%s: at most %d presets", at, startDefaultMost)
			}
			for _, p := range r.Then.Presets {
				if !idPattern.MatchString(p.Name) {
					return s.ID, fmt.Errorf("%s: preset %q: a-z0-9_-", at, p.Name)
				}
				if p.Weight < 0 {
					return s.ID, fmt.Errorf("%s: preset %s: weight cannot be negative", at, p.Name)
				}
			}
			if utf8.RuneCountInString(r.Then.Goal) > 40 {
				return s.ID, fmt.Errorf("%s: goal source name too long", at)
			}
		}
		return s.ID, nil
	}}
}

// StartDefaults are the manifest's contributions to start_defaults.
func (m *Manifest) StartDefaults() []StartDefaultsItem {
	var out []StartDefaultsItem
	_ = json.Unmarshal(m.Contributes["start_defaults"], &out)
	return out
}

// startDefaultSwitches checks that each contribution's when is a boolean field of the configuration.
func (m *Manifest) startDefaultSwitches() []string {
	var errs []string
	var fields []Field
	if sc, err := ParseSchema(m.Configuration); err == nil && sc != nil {
		fields = sc.Fields
	}
	for _, s := range m.StartDefaults() {
		if s.When == "" {
			continue
		}
		i := slices.IndexFunc(fields, func(c Field) bool { return c.Name == s.When })
		if i < 0 || fields[i].Type != "boolean" {
			errs = append(errs, fmt.Sprintf("contributes.start_defaults: %s: when %q is not a boolean field of configuration", s.ID, s.When))
		}
	}
	return errs
}
