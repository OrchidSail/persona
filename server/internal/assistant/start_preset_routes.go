package assistant

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/nwverse/persona/server/internal/apierr"
	"github.com/nwverse/persona/server/internal/extwire"
)

// startPresetRoutes: GET /api/v1/assistant/worker-presets?machine=<id> lists the Agent presets (the active model
// profile's tiers) a task can start with on that computer: the presets whose launcher the machine has, each with its
// launcher, model, effort, description and whether its launcher is unavailable now. An extension granted agents.read
// reads it to build its start_defaults presets; the list is read-only and never carries credentials.
func (s *Service) startPresetRoutes() {
	s.handle("GET /api/v1/assistant/worker-presets", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		machine := r.URL.Query().Get("machine")
		if _, ok := s.machineByName(ctx, machine); !ok {
			apierr.Write(w, http.StatusNotFound, errUnknownMachine)
			return
		}
		launchers, err := s.pickerLaunchers(ctx, machine)
		if err != nil {
			apierr.Write(w, http.StatusBadGateway, err)
			return
		}
		_, tiers := s.pickerTiers(ctx, machine, launchers)
		out := make([]workerPreset, 0, len(tiers))
		for _, t := range tiers {
			_, unavailable := s.launcherUnusable(ctx, machine, t.Launcher)
			out = append(out, workerPreset{
				Name: t.Name, Launcher: t.Launcher, Model: t.Model, Effort: t.Effort,
				Description: t.Description, Unavailable: unavailable,
			})
		}
		raw, _ := json.Marshal(map[string]any{"presets": out})
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_, _ = w.Write(raw)
	})
}

// workerPreset is one row of GET /api/v1/assistant/worker-presets.
type workerPreset struct {
	Name        string `json:"name"`
	Launcher    string `json:"launcher"`
	Model       string `json:"model,omitempty"`
	Effort      string `json:"effort,omitempty"`
	Description string `json:"description,omitempty"`
	// Unavailable: the launcher's latest check says it does not work right now (for example a usage limit with a
	// reset still ahead); absent check results leave it false, so an unchecked preset is offered.
	Unavailable bool `json:"unavailable,omitempty"`
}

var _ = extwire.ScopeAgentsRead // the route is held to agents.read in extwire.RouteScopes
