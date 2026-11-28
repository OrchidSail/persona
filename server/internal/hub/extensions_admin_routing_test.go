package hub

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/coder/websocket"

	"github.com/nwverse/persona/server/internal/assistantwire"
	"github.com/nwverse/persona/server/internal/bus"
	"github.com/nwverse/persona/server/internal/devicewire"
	"github.com/nwverse/persona/server/internal/extwire"
)

func TestAdminExtensionSandboxAndNetworkFollowSelectedAssistant(t *testing.T) {
	const team, offline = "m-1a2b3c", "m-4d5e6f"
	h, _, mux := adminTestHub(t)
	store := newMemManagerStore()
	h.bus.idents = store
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	type request struct {
		manager, method, path, host, body, admin string
	}
	relayed := make(chan request, 4)
	for _, id := range []string{assistantwire.AssistantID, team} {
		token := busToken(t, h, id, id)
		relay := &relayingExt{
			dial: func(ctx context.Context, query string) (*websocket.Conn, error) {
				return bus.DialAt(ctx, srv.URL, token, srv.Client(), devicewire.RelayPath, query)
			},
			serve: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				relayed <- request{id, r.Method, r.URL.Path, r.URL.Query().Get("host"), string(body), r.Header.Get(extwire.HeaderAdmin)}
				writeJSON(w, map[string]string{"id": "shared", "manager": id})
			}),
		}
		peer := dialRawBusWith(t, srv, token, func(ctx context.Context, method string, params json.RawMessage) (any, error) {
			if method == extwire.MethodAccept {
				out, _, err := relay.Request(ctx, method, params)
				return out, err
			}
			return map[string]any{}, nil
		})
		if err := peer.call(assistantwire.MethodHello, map[string]string{"id": id}, nil); err != nil {
			t.Fatal(err)
		}
	}
	_ = busToken(t, h, offline, "Offline assistant")
	in := signedIn(t, mux, ip4(192, 168, 1, 20)+":1")
	in.header = make(http.Header)
	const list = "/api/v1/admin/extensions"
	const detail = list + "/shared"
	checkRelay := func(method, path, query string, header []string, body, host, wantManager string) {
		t.Helper()
		in.header[ManagerHeader] = header
		if status, out, _ := in.do(method, path+query, body); status != http.StatusOK || out["manager"] != wantManager {
			t.Fatalf("%s %s%s: %d %v, want %s", method, path, query, status, out, wantManager)
		}
		select {
		case got := <-relayed:
			if want := (request{wantManager, method, path, host, body, "1"}); got != want {
				t.Fatalf("relayed %+v, want %+v", got, want)
			}
		default:
			t.Fatal("request did not reach a manager")
		}
	}
	checkRelay(http.MethodGet, list, "", nil, "", "", assistantwire.AssistantID)
	checkRelay(http.MethodGet, list, "?manager="+team, nil, "", "", team)
	checkRelay(http.MethodGet, detail, "?manager="+assistantwire.AssistantID, []string{team}, "", "", team)
	checkRelay(http.MethodGet, "/api/v1/admin/plugins/shared", "?manager="+team, nil, "", "", team)
	checkRelay(http.MethodPut, detail+"/sandbox", "?manager="+team, nil, `{"enabled":true}`, "", team)
	checkRelay(http.MethodPost, detail+"/egress/allow", "", []string{team}, `{"host":"source.example.org"}`, "", team)
	checkRelay(http.MethodDelete, detail+"/egress/allow", "?manager="+team+"&host=source.example.org", nil, "", "source.example.org", team)
	if err := store.PutSetting(t.Context(), managerDefaultKey, team); err != nil {
		t.Fatal(err)
	}
	h.ownersChanged()
	checkRelay(http.MethodGet, detail, "", nil, "", "", team)
	checkRelay(http.MethodGet, detail, "", []string{assistantwire.AssistantID}, "", "", assistantwire.AssistantID)

	for _, tc := range []struct {
		name, query string
		header      []string
		status      int
		code        string
	}{
		{"empty query selector", "?manager=", nil, http.StatusBadRequest, "invalid_manager"},
		{"empty header selector", "?manager=" + team, []string{" "}, http.StatusBadRequest, "invalid_manager"},
		{"malformed query encoding", "?manager=%ZZ", nil, http.StatusBadRequest, "invalid_manager"},
		{"malformed manager ID", "?manager=two%20words", nil, http.StatusBadRequest, "invalid_manager"},
		{"repeated query selector", "?manager=" + team + "&manager=" + assistantwire.AssistantID, nil, http.StatusBadRequest, "invalid_manager"},
		{"repeated header selector", "", []string{team, assistantwire.AssistantID}, http.StatusBadRequest, "invalid_manager"},
		{"unknown manager", "?manager=m-ffffff", nil, http.StatusNotFound, "no_manager"},
		{"unknown header takes precedence", "?manager=" + team, []string{"m-ffffff"}, http.StatusNotFound, "no_manager"},
		{"offline manager", "", []string{offline}, http.StatusServiceUnavailable, "assistant_offline"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			in.header[ManagerHeader] = tc.header
			status, out, _ := in.do(http.MethodPut, detail+"/sandbox"+tc.query, `{"enabled":false}`)
			if status != tc.status || out["code"] != tc.code {
				t.Errorf("HTTP %d %v, want %d %s", status, out, tc.status, tc.code)
			}
			select {
			case got := <-relayed:
				t.Errorf("refused request reached manager %s", got.manager)
			default:
			}
		})
	}
}
