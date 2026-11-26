package node

import (
	"context"
	"net"
	"strings"
	"testing"

	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/rpc"
)

func TestSessionStopRuntimeSessionIDs(t *testing.T) {
	newRuntimeNode := func(t *testing.T, listed bool, sessions []proto.Session) *Node {
		t.Helper()
		n, _, _ := herdrNode(t)
		compSide, nodeSide := net.Pipe()
		t.Cleanup(func() { _ = compSide.Close(); _ = nodeSide.Close() })
		nodePeer := rpc.New("node", nodeSide)
		n.runtimes = newRuntimeRegistry()
		companion := n.runtimes.add(nodePeer)
		companion.ready = true
		companion.sessionsListed = listed
		companion.hello = runtimeHello{Runtime: agyRuntimeID, Agent: agyAgent, Name: "Antigravity"}
		if len(sessions) > 0 {
			n.runtimes.setSessions(companion, sessions)
		}
		return n
	}

	t.Run("live_runtime_session_returns_no_terminal_instead_of_invalid_session_id", func(t *testing.T) {
		sid := "agy-aa11b24c-63e6-4362-a02f-71cc5ca5aea2"
		n := newRuntimeNode(t, true, []proto.Session{
			{SessionID: sid, Agent: agyAgent, Status: proto.StatusWorking},
		})
		_, err := request(t, n, "session.stop", map[string]any{"session_id": sid})
		if err == nil {
			t.Fatal("expected no_terminal refusal for live runtime session")
		}
		if code, _ := refusalOf(t, err); code != noTerminal {
			t.Fatalf("got code=%q err=%v, want %q", code, err, noTerminal)
		}
	})

	t.Run("absent_runtime_session_after_reboot_reports_not_running", func(t *testing.T) {
		sid := "agy-aa11b24c-63e6-4362-a02f-71cc5ca5aea2"
		n := newRuntimeNode(t, true, nil)
		res, err := request(t, n, "session.stop", map[string]any{"session_id": sid})
		if err != nil {
			t.Fatalf("expected gone runtime session stop to succeed as not running, got: %v", err)
		}
		if res["stopped"] != false {
			t.Fatalf("unexpected stop result: %#v", res)
		}
	})

	t.Run("pending_unlisted_runtime_fails_closed_with_runtime_away", func(t *testing.T) {
		sid := "agy-aa11b24c-63e6-4362-a02f-71cc5ca5aea2"
		n := newRuntimeNode(t, false, nil)
		_, err := request(t, n, "session.stop", map[string]any{"session_id": sid})
		if err == nil {
			t.Fatal("expected runtime_away while runtime has not pushed its first session list")
		}
		if code, _ := refusalOf(t, err); code != "runtime_away" {
			t.Fatalf("got code=%q err=%v, want runtime_away", code, err)
		}
	})

	t.Run("malformed_session_id_still_rejected", func(t *testing.T) {
		n := newRuntimeNode(t, true, nil)
		for _, bad := range []string{"../evil", "bad id", "not-a-known-runtime-prefix"} {
			_, err := request(t, n, "session.stop", map[string]any{"session_id": bad})
			if err == nil || !strings.Contains(err.Error(), "invalid session id") {
				t.Fatalf("session_id=%q: expected invalid session id, got: %v", bad, err)
			}
		}
	})

	_ = context.Background()
}
