package extension

import (
	"encoding/json"
	"testing"
)

const startDefaultsManifest = `{"manifest_version": 1, "id": "kit", "name": "Kit", "version": "1.0.0",
	"configuration": {"type": "object", "properties": {"on": {"type": "boolean", "title": "On", "default": true}, "name": {"type": "string", "title": "Name"}}},
	"contributes": {"start_defaults": [{"id": "power", "when": "on", "rules": [
		{"when": {"supervisor": ["leader"], "role": "worker", "readonly": false},
		 "then": {"presets": [{"name": "deep", "weight": 2}, {"name": "quick"}], "effort": "max", "ultracode": true, "goal": "done_when"}},
		{"when": {"supervisor": ["main"]}, "then": {"plan_first": true}}]}]}}`

// A start_defaults contribution parses into ordered rules with their match and defaults.
func TestStartDefaultsContributions(t *testing.T) {
	m, err := Parse([]byte(startDefaultsManifest))
	if err != nil {
		t.Fatal(err)
	}
	items := m.StartDefaults()
	if len(items) != 1 || items[0].ID != "power" || items[0].When != "on" || len(items[0].Rules) != 2 {
		t.Fatalf("%+v", items)
	}
	r := items[0].Rules[0]
	if len(r.When.Supervisor) != 1 || r.When.Supervisor[0] != "leader" || r.When.Role != "worker" || r.When.Readonly == nil || *r.When.Readonly {
		t.Fatalf("when = %+v", r.When)
	}
	if r.Then.Effort != "max" || r.Then.Ultracode == nil || !*r.Then.Ultracode || r.Then.Goal != "done_when" ||
		len(r.Then.Presets) != 2 || r.Then.Presets[0].Name != "deep" || r.Then.Presets[0].Weight != 2 {
		t.Fatalf("then = %+v", r.Then)
	}
	config := `"configuration": {"type": "object", "properties": {"on": {"type": "boolean", "title": "On"}, "name": {"type": "string", "title": "Name"}}},`
	bad := map[string]string{
		"no rules":                 `"start_defaults": [{"id": "x", "rules": []}]`,
		"a bad id":                 `"start_defaults": [{"id": "X", "rules": [{"then": {"effort": "max"}}]}]`,
		"an unknown supervisor":    `"start_defaults": [{"id": "x", "rules": [{"when": {"supervisor": ["owner"]}, "then": {}}]}]`,
		"an unknown role":          `"start_defaults": [{"id": "x", "rules": [{"when": {"role": "boss"}, "then": {}}]}]`,
		"an unknown effort":        `"start_defaults": [{"id": "x", "rules": [{"then": {"effort": "banana"}}]}]`,
		"an unknown goal source":   `"start_defaults": [{"id": "x", "rules": [{"then": {"goal": "whim"}}]}]`,
		"an unknown preset name":   `"start_defaults": [{"id": "x", "rules": [{"then": {"presets": [{"name": "Bad Name"}]}}]}]`,
		"a negative weight":        `"start_defaults": [{"id": "x", "rules": [{"then": {"presets": [{"name": "deep", "weight": -1}]}}]}]`,
		"a when that is not there": `"start_defaults": [{"id": "x", "when": "missing", "rules": [{"then": {}}]}]`,
		"a when that is a string":  `"start_defaults": [{"id": "x", "when": "name", "rules": [{"then": {}}]}]`,
		"an unknown field":         `"start_defaults": [{"id": "x", "rules": [{"when": {"team": 1}}]}]`,
	}
	for name, c := range bad {
		doc := `{"manifest_version": 1, "id": "kit", "name": "Kit", "version": "1.0.0", ` + config + ` "contributes": {` + c + `}}`
		if _, err := Parse([]byte(doc)); err == nil {
			t.Errorf("%s: parsed", name)
		}
	}
}

// A contribution with when holds while that field is true; without, always. Its switch is that setting.
func TestStartDefaultsSwitch(t *testing.T) {
	m, err := Parse([]byte(startDefaultsManifest))
	if err != nil {
		t.Fatal(err)
	}
	item := m.StartDefaults()[0]
	for raw, want := range map[string]bool{`{"on": true}`: true, `{"on": false}`: false, `{}`: false, `{"on": "yes"}`: false} {
		if item.Holds(json.RawMessage(raw)) != want {
			t.Errorf("%s: want %v", raw, want)
		}
	}
	if field, ok := m.SwitchedByConfig("start_defaults", "power"); !ok || field != "on" {
		t.Fatal(field, ok)
	}
	if _, ok := m.SwitchedByConfig("start_defaults", "missing"); ok {
		t.Fatal("an unknown contribution has no switch")
	}
}
