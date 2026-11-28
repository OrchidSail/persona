package extmgr

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/nwverse/persona/server/internal/connector"
	"github.com/nwverse/persona/server/internal/proto"
)

type callCtxProbeConnector struct {
	got []connector.CallContext
}

func (c *callCtxProbeConnector) ID() string   { return "probe" }
func (c *callCtxProbeConnector) Name() string { return "Probe" }

func (c *callCtxProbeConnector) Manifest() connector.Manifest {
	return connector.Manifest{
		Capabilities: []connector.Capability{connector.CapStatus, connector.CapTools},
		AgentTools:   []string{"probe_ping"},
	}
}

func (c *callCtxProbeConnector) Status(context.Context) connector.Status {
	return connector.Status{State: connector.StateOK}
}

func (c *callCtxProbeConnector) Tools() []proto.Tool {
	return []proto.Tool{{Name: "probe_ping"}}
}

func (c *callCtxProbeConnector) Call(ctx context.Context, _ string, _ json.RawMessage) (string, error) {
	cc, _ := connector.CallContextOf(ctx)
	c.got = append(c.got, cc)
	return "ok", nil
}

func TestCallContextConversationIDPassthrough(t *testing.T) {
	h := newTestManager(t)
	probe := &callCtxProbeConnector{}
	h.connectors = connector.NewRegistry(nil)
	if err := h.connectors.Add(probe); err != nil {
		t.Fatal(err)
	}

	// 1. Assistant conversation call carries ConversationID.
	res, handled := h.CallTool(context.Background(), "box", "conv-origin-1", "probe_ping", json.RawMessage(`{}`), true)
	if !handled || res.IsError {
		t.Fatalf("assistant call failed: handled=%v res=%+v", handled, res)
	}
	if len(probe.got) != 1 || probe.got[0].ConversationID != "conv-origin-1" {
		t.Fatalf("assistant call context = %+v, want conversation_id=conv-origin-1", probe.got)
	}

	// 2. Non-assistant (agent) call leaves ConversationID empty.
	res, handled = h.CallTool(context.Background(), "box", "agent-sess-1", "probe_ping", json.RawMessage(`{}`), false)
	if !handled || res.IsError {
		t.Fatalf("agent call failed: handled=%v res=%+v", handled, res)
	}
	if len(probe.got) != 2 || probe.got[1].ConversationID != "" {
		t.Fatalf("agent call context = %+v, want empty conversation_id", probe.got)
	}

	// 3. Empty session call leaves ConversationID empty.
	res, handled = h.CallTool(context.Background(), "box", "", "probe_ping", json.RawMessage(`{}`), true)
	if !handled || res.IsError {
		t.Fatalf("empty session call failed: handled=%v res=%+v", handled, res)
	}
	if len(probe.got) != 3 || probe.got[2].ConversationID != "" {
		t.Fatalf("empty session call context = %+v, want empty conversation_id", probe.got)
	}

	// 4. Direct callConnector with owner caller populates ConversationID from asker.session.
	ownerCtx := connector.WithOwnerCaller(context.Background())
	res = h.callConnector(ownerCtx, asker{machine: "box", session: "conv-direct-2"}, "probe_ping", json.RawMessage(`{}`))
	if res.IsError {
		t.Fatalf("direct owner call failed: %+v", res)
	}
	if len(probe.got) != 4 || probe.got[3].ConversationID != "conv-direct-2" {
		t.Fatalf("direct owner call context = %+v, want conversation_id=conv-direct-2", probe.got)
	}
}
