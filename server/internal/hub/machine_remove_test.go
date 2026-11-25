package hub

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func diskMachineIDs(t *testing.T, h *Hub) []string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(h.cfg.dir, "hub.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Machines []struct {
			ID string `json:"id"`
		} `json:"machines"`
	}
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	out := make([]string, 0, len(f.Machines))
	for _, m := range f.Machines {
		out = append(out, m.ID)
	}
	return out
}

// A signed-in full device removes a paired computer through DELETE /api/v1/machines/{id}; the hub advertises
// machine_remove, removes the hub.json entry, reports restart.required, and rejects unknown or non-full callers.
func TestDeviceRemovesAComputer(t *testing.T) {
	h := renameHub(t)
	if !slices.Contains(h.features(), featureMachineRemove) {
		t.Fatal("the hub does not advertise machine_remove")
	}
	code, out := call(t, h.routes(), "DELETE", "/api/v1/machines/ren-b", h.cfg.Token, "")
	if code != http.StatusOK || out["ok"] != true {
		t.Fatalf("DELETE /api/v1/machines/ren-b: %d %v", code, out)
	}
	restart, ok := out["restart"].(map[string]any)
	if !ok || restart["required"] != true {
		t.Fatalf("expected restart.required=true for hub.json removal, got %v", out["restart"])
	}
	if got := diskMachineIDs(t, h); !slices.Equal(got, []string{"ren-a"}) {
		t.Fatalf("hub.json machines after delete = %v, want [ren-a]", got)
	}

	// Removing an unknown computer (or already removed) answers 404 not_found.
	code, out = call(t, h.routes(), "DELETE", "/api/v1/machines/ren-b", h.cfg.Token, "")
	if code != http.StatusNotFound || out["code"] != "not_found" {
		t.Fatalf("second delete: %d %v", code, out)
	}
	code, out = call(t, h.routes(), "DELETE", "/api/v1/machines/unknown", h.cfg.Token, "")
	if code != http.StatusNotFound || out["code"] != "not_found" {
		t.Fatalf("unknown machine: %d %v", code, out)
	}
}

// Non-full devices (answer-only or pairing stand-ins) receive 403 forbidden and cannot remove any computer.
func TestDeviceRemoveRequiresFullPrincipal(t *testing.T) {
	h := renameHub(t)
	mux := http.NewServeMux()
	h.machineRemoveRoutes(mux)

	for _, tc := range []struct {
		name string
		p    principal
	}{
		{name: "answer_role", p: principal{ID: "dev-watch", Role: roleAnswer}},
		{name: "pair_standin", p: principal{ID: "dev-phone", Role: roleFull, PairHash: "pair-hash"}},
		{name: "extension_role", p: principal{ID: "ext-1", Role: roleExtension}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest("DELETE", "/api/v1/machines/ren-a", nil)
			req = req.WithContext(context.WithValue(req.Context(), principalKey{}, tc.p))
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code != http.StatusForbidden {
				t.Fatalf("status=%d body=%s, want 403", rec.Code, rec.Body.String())
			}
			var out map[string]any
			if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
				t.Fatal(err)
			}
			if out["code"] != "forbidden" {
				t.Fatalf("code=%v, want forbidden", out["code"])
			}
		})
	}
	if got := diskMachineIDs(t, h); !slices.Equal(got, []string{"ren-a", "ren-b"}) {
		t.Fatalf("hub.json mutated on forbidden call: %v", got)
	}
}
