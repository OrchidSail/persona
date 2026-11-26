package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/apierr"
	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

func TestStopContinuedTaskAgentsHandlesMissingAndRuntimeSessions(t *testing.T) {
	newFixture := func(t *testing.T, sid string, listed []proto.Session, nodeFn func(method string, params json.RawMessage) (any, error)) (*threadEnv, Todo) {
		t.Helper()
		e := newThreadEnv(t, nil)
		ctx := context.Background()
		e.f.devices = []Device{
			{ID: "bell", Name: "Bell", Online: true, Reachable: true},
			{ID: "honor", Name: "honor", Online: true, Reachable: true},
		}
		e.sessions("bell", listed...)
		e.sessions("honor")
		todo, err := e.s.createTaskTodo(ctx, "Continue task", "Fix bug.", "m1", "", "")
		if err != nil {
			t.Fatal(err)
		}
		if err := e.s.linkTodoAgent(ctx, todo.ID, "bell", sid, todo.Title, "/code/app", "agy"); err != nil {
			t.Fatal(err)
		}
		if nodeFn != nil {
			e.f.nodes = map[string]nodeAnswer{
				deviceSessionStop: func(_ string, p json.RawMessage) (any, error) { return nodeFn(deviceSessionStop, p) },
				"session.close":   func(_ string, p json.RawMessage) (any, error) { return nodeFn("session.close", p) },
			}
		}
		return e, todoNow(e.s, todo.ID)
	}

	t.Run("online_node_invalid_session_id_releases_gone_runtime_agent", func(t *testing.T) {
		sid := "agy-aa11b24c-63e6-4362-a02f-71cc5ca5aea2"
		e, todo := newFixture(t, sid, nil, func(method string, _ json.RawMessage) (any, error) {
			return nil, &rpc.Error{Message: `invalid session id "` + sid + `"`}
		})
		stopped, err := e.s.stopContinuedTaskAgents(context.Background(), todo)
		if err != nil {
			t.Fatalf("expected missing runtime session on online node to be treated as stopped, got: %v", err)
		}
		if len(stopped) != 1 || stopped[0].SessionID != sid {
			t.Fatalf("stopped = %+v, want [%s]", stopped, sid)
		}
	})

	t.Run("online_node_not_found_code_releases_gone_session", func(t *testing.T) {
		sid := "agy-11111111-2222-3333-4444-555555555555"
		e, todo := newFixture(t, sid, nil, func(method string, _ json.RawMessage) (any, error) {
			return nil, &rpc.Error{Code: "not_found", Message: "session not found"}
		})
		stopped, err := e.s.stopContinuedTaskAgents(context.Background(), todo)
		if err != nil {
			t.Fatalf("expected not_found on online node to be treated as stopped, got: %v", err)
		}
		if len(stopped) != 1 || stopped[0].SessionID != sid {
			t.Fatalf("stopped = %+v, want [%s]", stopped, sid)
		}
	})

	t.Run("no_terminal_falls_back_to_session_close", func(t *testing.T) {
		sid := "agy-22222222-2222-3333-4444-555555555555"
		var methods []string
		e, todo := newFixture(t, sid, []proto.Session{
			{SessionID: sid, MachineID: "bell", Agent: "agy", Status: proto.StatusWorking},
		}, func(method string, _ json.RawMessage) (any, error) {
			methods = append(methods, method)
			if method == deviceSessionStop {
				return nil, &rpc.Error{Code: "no_terminal", Message: "this session belongs to another tool"}
			}
			return map[string]any{"ok": true}, nil
		})
		stopped, err := e.s.stopContinuedTaskAgents(context.Background(), todo)
		if err != nil {
			t.Fatalf("expected no_terminal -> session.close fallback to succeed, got: %v", err)
		}
		if len(stopped) != 1 || len(methods) != 2 || methods[0] != deviceSessionStop || methods[1] != "session.close" {
			t.Fatalf("methods=%v stopped=%+v", methods, stopped)
		}
	})

	t.Run("fail_closed_on_offline_timeout_refusal_and_live_session", func(t *testing.T) {
		sid := "agy-33333333-2222-3333-4444-555555555555"
		cases := []struct {
			name   string
			listed []proto.Session
			retErr error
		}{
			{"offline_error", nil, apierr.ErrOffline},
			{"no_device_error", nil, ErrNoDevice},
			{"timeout_error", nil, context.DeadlineExceeded},
			{"rpc_closed", nil, rpc.ErrClosed},
			{"runtime_away", nil, &rpc.Error{Code: "runtime_away", Message: "Antigravity is not connected"}},
			{"not_stopped", nil, &rpc.Error{Code: "not_stopped", Message: "the agent did not quit"}},
			{"not_retirable", nil, &rpc.Error{Code: "not_retirable", Message: "still starting"}},
			{"safety_refusal_rpc", nil, &rpc.Error{Code: "safety_refusal", Message: "blocked"}},
			{"unresolved_approval_rpc", nil, &rpc.Error{Code: "unresolved_approval", Message: "pending"}},
			{"plain_non_rpc_error", nil, errors.New(`invalid session id "` + sid + `"`)},
			{"invalid_session_id_while_still_working_in_list", []proto.Session{
				{SessionID: sid, MachineID: "bell", Agent: "agy", Status: proto.StatusWorking},
			}, &rpc.Error{Message: `invalid session id "` + sid + `"`}},
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				e, todo := newFixture(t, sid, tc.listed, func(method string, _ json.RawMessage) (any, error) {
					return nil, tc.retErr
				})
				if _, err := e.s.stopContinuedTaskAgents(context.Background(), todo); err == nil || !strings.Contains(err.Error(), "cannot stop agent") {
					t.Fatalf("%s: expected fail-closed stop error, got: %v", tc.name, err)
				}
			})
		}
	})
}
