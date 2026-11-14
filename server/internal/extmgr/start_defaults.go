package extmgr

import (
	"github.com/nwverse/persona/server/internal/extension"
)

// StartDefaultItems are the start_defaults contributions that hold now: of the extensions that are on, each
// contribution whose when field (if any) is true under its saved configuration. Rules keep their manifest order; the
// caller matches a task against them. Never nil: an empty list means no extension changes task starts.
func (h *Manager) StartDefaultItems() []extension.StartDefaultsItem {
	out := []extension.StartDefaultsItem{}
	if h.extensions == nil || h.extensions.reg == nil {
		return out
	}
	h.extensions.declared(func(p *extension.Extension) {
		config := h.extensions.reg.Config(p)
		for _, item := range p.Manifest.StartDefaults() {
			if item.Holds(config) {
				out = append(out, item)
			}
		}
	})
	return out
}
