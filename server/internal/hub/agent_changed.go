package hub

import (
	"context"
	"slices"
	"sync"

	"github.com/nwverse/persona/server/internal/proto"
)

// agent.changed: the extensions' wake-up for a session they may read (docs/extensions/extensions.md "Agent changes").
// It says which session changed and whether a turn of it just ended, and nothing of what changed: the extension reads
// the session again through the routes its scopes open. The hub sends it from what it is told anyway (the manager's
// session list, its cards, a node's Stop hook): it starts no watch on the computer and reads no transcript.
//
// Whether the extensions are shown the session, and whose it is, are checked where the change is noted, in the
// caller's own turn, with the hub's own rules (extension_private.go). A change may then wait behind a slow manager,
// so the boundary of what is taken back is this:
//
//	An event the hub has not begun to write is withdrawn when its session becomes hidden, leaves the list or its
//	computer goes to another manager. An event the hub has begun to write to the manager's connection is delivered:
//	the change it reports happened while the session was still shown.
//
// Three things hold it. An event still in the backlog is taken out of it (agentChanges.rows, machineHandedOver). The
// one taken out to be sent is checked against the list the apps got last and the computer's manager now
// (agentStillTold). And while that one waits for the connection's writer its send is cancelled by the same withdrawal
// (agentChanges.stop, notifyBusContext): the writer checks once more after it is free, and a line it has begun is
// written to its end. The bus between the hub and a manager is never framed (only a node's connection is,
// rpc.Peer.EnableLanes), so the writer is the one line at a time of rpc.Peer. What the manager does with a delivered
// event, on its way to the extension's process, is in transit and not taken back.
//
// The goroutine that sends reads the backlog's own record of the list, never a computer's sessions, which the session
// list's writers hold.

// eventAgentChanged is the topic (extension.EventTopics; it needs agents.read, extension.EventScopes).
const eventAgentChanged = "agent.changed"

// agentChanged is the event's whole payload: no title, no state, no text.
type agentChanged struct {
	MachineID string `json:"machine_id"`
	SessionID string `json:"session_id"`
	// Ended: a turn of the session just ended (its status left working, or its Stop hook ran).
	Ended bool `json:"ended,omitempty"`
}

// agentKey is one session, and the manager its computer belonged to when it changed: that manager's extensions are
// the ones told.
type agentKey struct{ owner, machine, session string }

// agentState is what of a session's row the event is about: the fields a client reads to tell whether it works, waits
// for its owner or stopped. Not the transcript's size or revision, which move with every step.
type agentState struct {
	status, state, turnError, phase string
	starting                        bool
	pending, background             int
	subagents                       uint64 // which subagents it has and which of them run (agentSubagents)
}

func agentStateOf(s *proto.Session) agentState {
	st := agentState{status: s.Status, state: s.State, turnError: s.TurnError, starting: s.Starting,
		pending: s.PendingInteractions, background: len(s.Background), subagents: agentSubagents(s.Subagents)}
	if s.Work != nil {
		st.phase = s.Work.Phase
	}
	return st
}

// agentSubagents is a hash (FNV-1a) of the subagents' ids and whether each runs: one starting or finishing changes
// it, a step of one (its last text, its tool calls, its tokens) does not. 0 for none.
func agentSubagents(list []proto.Subagent) uint64 {
	if len(list) == 0 {
		return 0
	}
	const offset, prime = 14695981039346656037, 1099511628211
	h := uint64(offset)
	for i := range list {
		for j := 0; j < len(list[i].ID); j++ {
			h = (h ^ uint64(list[i].ID[j])) * prime
		}
		running := uint64(1)
		if list[i].Running {
			running = 2
		}
		h = (h ^ running) * prime
	}
	return h
}

// agentChanges is what the hub still has to tell the extensions, and what it last saw of each listed session.
//
// The backlog is keyed by session: while the send of one is under way (the bus to a manager is written to in turn, and
// a slow manager holds the writer), every further change of a session waiting is folded into the one already there,
// its ended or-ed. So it holds at most one event a session, however fast the session moves.
type agentChanges struct {
	mu sync.Mutex
	// seen: machine → session → its state, and where it runs, in the list the apps got last. A machine with no entry
	// has had no list yet: its first one is where the hub starts from, and tells of nothing.
	seen    map[string]map[string]agentSeen
	pass    uint64 // the list being compared (rows)
	pending map[agentKey]bool
	order   []agentKey // pending's keys, first changed first
	running bool       // a drain is under way
	// sending is the event taken out of the backlog whose send is under way, sendCtx what its send goes by and stop
	// what ends sendCtx: a withdrawal that names sending calls it, and the send gives up unless the write has begun.
	// stop is nil while none is being sent.
	sending agentKey
	sendCtx context.Context
	stop    context.CancelFunc
}

type agentSeen struct {
	state agentState
	pass  uint64
	// space and cwd are the row's, what tells a space's conversation (spaceRow): kept so that an event waiting is
	// checked against them as it is sent, without reading the computer's list.
	space, cwd string
}

// rows compares machine's list, as the apps get it, with the one before and returns the sessions whose state changed
// (ended: its status left working), a session new to the list among them. Never a space's conversation
// (spaceSession, as sessionHiddenFromExtensions has it): nothing is told of it, and one that leaves its space is told
// of as a session new to the list, never as a turn that ended while it was hidden. A session gone
// from the list is forgotten and not told of: the hub can no longer say whether the extensions are shown it. An event
// still waiting for a session that is gone from the list, or is a space's now, is withdrawn.
func (q *agentChanges) rows(machine string, list []proto.Session) (changed []agentChanged) {
	q.mu.Lock()
	defer q.mu.Unlock()
	prev, known := q.seen[machine]
	if !known {
		if q.seen == nil {
			q.seen = map[string]map[string]agentSeen{}
		}
		prev = make(map[string]agentSeen, len(list))
		q.seen[machine] = prev
	}
	q.pass++
	for i := range list {
		s := &list[i]
		now := agentStateOf(s)
		old, had := prev[s.SessionID]
		if had && (old.space != s.Space || old.cwd != s.Cwd) && spaceRow(old.space, old.cwd) {
			had = false // it was a space's conversation: out of the space, it is new to what the extensions are shown
		}
		prev[s.SessionID] = agentSeen{state: now, pass: q.pass, space: s.Space, cwd: s.Cwd}
		if !known || had && old.state == now || spaceSession(s) {
			continue
		}
		changed = append(changed, agentChanged{MachineID: machine, SessionID: s.SessionID,
			Ended: had && old.state.status == proto.StatusWorking && now.status != proto.StatusWorking})
	}
	if len(prev) > len(list) {
		for id, was := range prev {
			if was.pass != q.pass {
				delete(prev, id)
			}
		}
	}
	if len(q.pending) > 0 || q.stop != nil {
		q.withdrawLocked(func(k agentKey) bool {
			if k.machine != machine {
				return false
			}
			row, listed := prev[k.session]
			return !listed || spaceRow(row.space, row.cwd)
		})
	}
	return changed
}

// withdrawLocked takes the events waiting that gone names out of the backlog: they are not sent. The one being sent,
// if gone names it, is stopped: its send gives up unless its write has begun (stop only ends a context: it never
// waits, and takes no lock). q.mu is held.
func (q *agentChanges) withdrawLocked(gone func(agentKey) bool) {
	n := len(q.pending)
	for k := range q.pending {
		if gone(k) {
			delete(q.pending, k)
		}
	}
	if len(q.pending) != n {
		q.order = slices.DeleteFunc(q.order, func(k agentKey) bool { _, waiting := q.pending[k]; return !waiting })
	}
	if q.stop != nil && gone(q.sending) {
		q.stop()
	}
}

// handedOver withdraws the events of computer machine waiting for another manager's extensions than owner's: the
// computer is owner's now, and its sessions are no longer theirs to hear of.
func (q *agentChanges) handedOver(machine, owner string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.withdrawLocked(func(k agentKey) bool { return k.machine == machine && k.owner != owner })
}

// shown says whether session sid of computer machine is in the list the apps got last and is not a space's
// conversation there: what an event is checked against as it is sent. Stricter than sessionHiddenFromExtensions (a
// row left out of the apps' list is not shown either), and read from the backlog's own record.
func (q *agentChanges) shown(machine, sid string) bool {
	q.mu.Lock()
	row, listed := q.seen[machine][sid]
	q.mu.Unlock()
	return listed && !spaceRow(row.space, row.cwd)
}

// mark puts a change of k in the backlog, folded into the one waiting if there is one; start: nothing drains it yet,
// the caller starts a drain.
func (q *agentChanges) mark(k agentKey, ended bool) (start bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if was, waiting := q.pending[k]; waiting {
		q.pending[k] = was || ended
		return false
	}
	if q.pending == nil {
		q.pending = map[agentKey]bool{}
	}
	q.pending[k] = ended
	q.order = append(q.order, k)
	if q.running {
		return false
	}
	q.running = true
	return true
}

// drain sends the backlog, the session that changed first first, until it is empty. A change marked while one is
// being sent is sent after it. send is called with no lock held: it checks once more that the event is still its
// receiver's to hear (sendAgentChanged) and may wait on the bus. The event is noted as the one being sent as it is
// taken out, under the same lock, so no withdrawal can fall between the backlog and the send.
func (q *agentChanges) drain(send func(k agentKey, ended bool)) {
	for {
		q.mu.Lock()
		if len(q.order) == 0 {
			q.order, q.running = nil, false
			q.mu.Unlock()
			return
		}
		k := q.order[0]
		q.order = q.order[1:]
		ended, waiting := q.pending[k]
		delete(q.pending, k)
		if waiting {
			q.sending = k
			q.sendCtx, q.stop = context.WithCancel(context.Background())
		}
		q.mu.Unlock()
		if !waiting {
			continue
		}
		send(k, ended)
		q.mu.Lock()
		q.stop()
		q.sending, q.sendCtx, q.stop = agentKey{}, nil, nil
		q.mu.Unlock()
	}
}

// sendContext is what the send of k goes by: ended by a withdrawal that names k while it is the one being sent. Sent
// by anything but the drain (a test), k has none to be withdrawn by.
func (q *agentChanges) sendContext(k agentKey) context.Context {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.stop != nil && q.sending == k {
		return q.sendCtx
	}
	return context.Background()
}

// agentChanged tells manager owner's extensions that session sid of computer machine changed, soon: the caller is
// not held (it may hold the session list's lock, or be a node's event in its turn). The caller has checked that the
// extensions are shown the session.
func (h *Hub) agentChanged(owner, machine, sid string, ended bool) {
	if machine == "" || sid == "" {
		return
	}
	if h.agentChanges.mark(agentKey{owner, machine, sid}, ended) {
		go h.agentChanges.drain(h.sendAgentChanged)
	}
}

// agentRowsChanged tells the extensions of the sessions of m, owner's computer, whose state changed in list, the one
// the apps are being sent (broadcastSessions).
func (h *Hub) agentRowsChanged(owner string, m *Machine, list []proto.Session) {
	for _, c := range h.agentChanges.rows(m.cfg.ID, list) {
		h.agentChanged(owner, c.MachineID, c.SessionID, c.Ended)
	}
}

// agentTurnEnded tells the extensions that a turn of session sid of computer machine ended (its Stop hook ran).
// Never of a session they are not shown (sessionHiddenFromExtensions: one of a space, or one the hub does not list).
func (h *Hub) agentTurnEnded(machine, sid string) {
	if sid == "" || h.sessionHiddenFromExtensions(machine, sid) {
		return
	}
	h.agentChanged(h.machineOwner(machine), machine, sid, true)
}

// agentCardChanged tells the extensions that a card of a session was raised or is over. Never of a card they are not
// shown (hiddenFromExtensions: a private one, one of a space), of a session they are not shown
// (sessionHiddenFromExtensions), nor of a display copy, whose own card says it of the session it belongs to.
func (h *Hub) agentCardChanged(ix proto.Interaction) {
	if ix.MirrorOf != "" || ix.MachineID == "" || ix.SessionID == "" || h.hiddenFromExtensions(ix) ||
		h.sessionHiddenFromExtensions(ix.MachineID, ix.SessionID) {
		return
	}
	h.agentChanged(h.machineOwner(ix.MachineID), ix.MachineID, ix.SessionID, false)
}

// sendAgentChanged sends one event to the extensions of the manager it was noted for, and only that manager's
// (emitToExtensionsContext), unless it is no longer theirs to hear (agentStillTold): it may have waited. If it is
// withdrawn while it waits for the connection's writer, it is not written (sendContext).
func (h *Hub) sendAgentChanged(k agentKey, ended bool) {
	ctx := h.agentChanges.sendContext(k)
	if !h.agentStillTold(k) {
		return
	}
	h.emitToExtensionsContext(ctx, k.owner, eventAgentChanged, agentChanged{MachineID: k.machine, SessionID: k.session, Ended: ended})
}

// agentStillTold checks an event as it is sent: its session is still one the extensions are shown (agentChanges.shown)
// and its computer is still the manager's the event was noted for. Called by the sender with no lock held; each read
// takes its own lock (the backlog's, the devices'), never a computer's or the session list's.
func (h *Hub) agentStillTold(k agentKey) bool {
	return h.agentChanges.shown(k.machine, k.session) && h.machineOwner(k.machine) == k.owner
}
