package extmgr

import (
	"context"

	"github.com/nwverse/persona/server/internal/connector"
)

// legacyConnectors had rows of their own in Settings › Connectors before the app drew them from GET /api/v1/connectors
// (migration M6): an app from before draws every connector listed as a row of its own, so they are listed only when
// asked for (?all=1), or it would show them twice.
var legacyConnectors = map[string]bool{"lark": true, "mail": true}

func (h *Manager) listedConnectors(ctx context.Context, all bool) []connector.Info {
	h.requestLarkCLIRecovery(ctx)
	out := []connector.Info{}
	for _, info := range h.connectors.Infos(ctx) {
		if info.ID == "github" && info.Options != nil {
			info.Options = h.withGitHubMutes(ctx, info.Options)
		}
		if info.ExtensionID == "" && h.extensions != nil {
			if c := h.connectors.Get(info.ID); c != nil {
				info.ExtensionID = h.extensions.extensionIDFor(c)
			}
		}
		if all || !legacyConnectors[info.ID] {
			out = append(out, info)
		}
	}
	return out
}

// extensionIDFor returns the id of the extension that currently owns connector c, or "" for built-in connectors.
func (ps *extensions) extensionIDFor(c connector.Connector) string {
	if ps == nil || c == nil {
		return ""
	}
	ps.mu.Lock()
	defer ps.mu.Unlock()
	for eid, hp := range ps.byID {
		for _, own := range hp.conns {
			if connector.Connector(own) == c {
				if hp.id != "" {
					return hp.id
				}
				return eid
			}
		}
	}
	return ""
}
