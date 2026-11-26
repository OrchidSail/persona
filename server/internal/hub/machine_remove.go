package hub

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/nwverse/persona/server/internal/i18n"
)

// Removing a computer (docs/protocol/protocol.md "Removing a computer"): the admin page (DELETE /api/v1/admin/machines/{id})
// and a signed-in full device (DELETE /api/v1/machines/{id}) share one removal path. Persona Link credentials and unused
// pairing codes are revoked immediately; a manager-kept entry leaves the live list at once, while a hub.json entry waits
// for a hub restart.
const featureMachineRemove = "machine_remove"

// mayRemoveMachine: whether a computer may be removed by p's request (a signed-in full device, not a pairing stand-in,
// or the master token), matching confirm / reject / rename.
func mayRemoveMachine(p principal) bool { return mayTrust(p) }

// removeMachine removes computer id on behalf of actor via ("admin" | "device") and returns the hub's pending restart
// status in lang. Coded refusals come back as *addMachineError so HTTP callers write localized error bodies uniformly.
func (h *Hub) removeMachine(ctx context.Context, id, actor, via string, lang i18n.Lang) (restartView, error) {
	// Not while it switches (its manager runs the switch, and undoes it on failure), and no switch begins until
	// this is done (link_switch.go).
	release, err := h.holdLinkSwitch(ctx, id)
	if err != nil {
		return restartView{}, err
	}
	defer release()
	// One waiting to be confirmed goes as rejecting it does: its pending row and place too (machine_trust.go).
	if h.machinePending(id) {
		switch err := h.rejectMachine(ctx, id, actor, "remove"); {
		case err == nil:
			return h.pendingRestart(lang), nil
		case errors.Is(err, errKeeperAway):
			return restartView{}, refuseAdd(http.StatusServiceUnavailable, "assistant_offline", err)
		case !errors.Is(err, errNotPending): // confirmed meanwhile: removed below as one
			return restartView{}, err
		}
	}
	// Its Persona Link goes now, before the entry: until a restart a hub.json entry is still there to connect to,
	// and a credential left under the id would let a computer added with it again back in. One that cannot be
	// revoked leaves the entry, to delete again.
	revoked, err := h.revokeComputer(ctx, id)
	if err != nil {
		return restartView{}, err
	}
	auditRemoved := func(where string) restartView {
		payload := map[string]any{"credentials_revoked": revoked}
		if via != "" && via != "admin" {
			payload["via"] = via
		}
		detail, _ := json.Marshal(payload)
		h.machineAudit(ctx, id, "remove", actor, detail)
		h.logf("%s: machine %q removed%s", via, id, where)
		return h.pendingRestart(lang)
	}
	// One its manager keeps: it lets it go now (machines_kept.go).
	if kept, err := h.removeKeptMachine(ctx, id); kept {
		if err != nil {
			status, code := keeperErr(err)
			return restartView{}, refuseAdd(status, code, err)
		}
		return auditRemoved(""), nil
	}
	found, err := removeMachineEntry(h.cfg.dir, id)
	if err != nil {
		return restartView{}, err
	}
	if !found {
		return restartView{}, refuseAdd(http.StatusNotFound, "not_found", i18n.Err("hub-machine_trust.no_machine"))
	}
	h.clearKept(ctx, id) // an old record of a manager that kept it (machines_kept.go)
	return auditRemoved(" from hub.json"), nil
}

// machineRemoveRoutes: a signed-in full device removes a computer (the apps' machine details).
func (h *Hub) machineRemoveRoutes(api *http.ServeMux) {
	api.HandleFunc("DELETE /api/v1/machines/{id}", func(w http.ResponseWriter, r *http.Request) {
		p := principalOf(r.Context())
		if !mayRemoveMachine(p) {
			writeErrCode(w, http.StatusForbidden, "forbidden", i18n.Err("hub-machine_remove.forbidden"))
			return
		}
		restart, err := h.removeMachine(r.Context(), r.PathValue("id"), trustActor(p), "device", h.requestLang(r))
		if err != nil {
			writeAddMachineErr(w, err)
			return
		}
		writeJSON(w, map[string]any{"ok": true, "restart": restart})
	})
}
