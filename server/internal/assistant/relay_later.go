package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"time"

	"github.com/nwverse/persona/server/internal/prompts"
	"github.com/nwverse/persona/server/internal/proto"
)

// relayLaterSend is the quiet context reserved before the first node write under a delivery ID (an assistant.send
// once_id or a scoped owner client_msg_id). An empty reservation matters too: a retry of a send that originally
// carried nothing must not consume newly arrived quiet messages.
type relayLaterSend struct {
	At    time.Time  `json:"at"`
	Text  string     `json:"text,omitempty"`
	Items []relayMsg `json:"items,omitempty"`
}

// ownerLaterSend is an in-flight owner send prepared by assistant.send_params waiting for session.assistant_sent.
type ownerLaterSend struct {
	msg  string
	done func(bool)
	at   time.Time
}

// conversation_send with wake: false (#11614, #12867). Every turn of a supervising conversation re-reads its whole
// context, so a message that only acknowledges or informs should not start one. Such a message is kept for its
// receiver (relayState.Later) and goes in, as a context block in front of it, with the next message delivered there
// for any other reason: a supervision message, another conversation's message, a scheduled follow-up, a notice, or an
// owner message sent from an app/web client (via assistant.send_params and settled on session.assistant_sent, or
// retried from undelivered). It never starts a turn of its own, costs no hop and no place in the relay rate, and is
// never guessed from its words. While kept, it shows above the composer (queuedRelays) and clears once consumed.

const (
	// relayLaterMax bounds what one conversation keeps; past it the oldest goes.
	relayLaterMax = 20
	// relayLaterKeep is how long a kept message waits for a delivery to go with; a conversation that got nothing in
	// that long (deleted, abandoned) lets it go.
	relayLaterKeep = 7 * 24 * time.Hour
	// ownerLaterInFlightMost bounds in-flight hub owner sends tracked per conversation for unnamed/legacy settlement.
	ownerLaterInFlightMost = 8
	// ownerLaterWait is how long an in-flight hub owner send waits for session.assistant_sent before expiring.
	ownerLaterWait = 10 * time.Minute
)

func relayLaterKey(to, once string) string {
	identity, _ := json.Marshal([2]string{to, once})
	return string(identity) // JSON strings are safe as Postgres jsonb keys, unlike a NUL separator.
}

// relayLaterOwnerOnce scopes an app message's client_msg_id in LaterSends so it never collides with a manager
// assistant.send once_id (relay_once.go).
func relayLaterOwnerOnce(clientMsgID string) string {
	if clientMsgID == "" {
		return ""
	}
	return "owner:" + clientMsgID
}

// keepRelayLater keeps msg for conversation to, answering how many wait there now; false when the relay state could not
// be read (nothing was kept).
func (s *Service) keepRelayLater(to string, msg relayMsg) (int, bool) {
	b := &s.relays
	b.mu.Lock()
	defer b.mu.Unlock()
	if !s.relayLocked(context.Background()) {
		return 0, false
	}
	if b.st.Later == nil {
		b.st.Later = map[string][]relayMsg{}
	}
	msg.QueuedAt = time.Now().UTC()
	q := append(b.st.Later[to], msg)
	if len(q) > relayLaterMax {
		q = slices.Clone(q[len(q)-relayLaterMax:])
	}
	b.st.Later[to] = q
	if err := s.saveRelayLocked(); err != nil {
		b.loaded = false // reload durable state before another send or retry
		return 0, false
	}
	return len(q), true
}

// relayLaterFor renders what is kept for conversation to ("" when nothing), and a done that drops exactly those once
// the message carrying them went in (taken). What came meanwhile stays for the next delivery; a send that failed or is
// in doubt keeps them all, so one is at worst read twice, never lost.
func (s *Service) relayLaterFor(ctx context.Context, to string) (string, func(taken bool)) {
	text, done, _ := s.relayLaterSendFor(ctx, to, "")
	return text, done
}

func (s *Service) relayLaterSendFor(ctx context.Context, to, once string) (string, func(bool), error) {
	loc := s.location(ctx)
	language := answerLanguage(s.hello(ctx).Locale)
	b := &s.relays
	b.mu.Lock()
	if !s.relayLocked(ctx) {
		b.mu.Unlock()
		return "", func(bool) {}, errors.New("cannot read quiet-message delivery state")
	}
	s.pruneRelayLaterLocked(time.Now())
	key := relayLaterKey(to, once)
	carry, reserved := b.st.LaterSends[key]
	if once == "" || !reserved {
		carry = relayLaterSend{At: time.Now().UTC(), Items: slices.Clone(b.st.Later[to])}
		items := make([]prompts.RelayLaterItem, 0, len(carry.Items))
		for _, m := range carry.Items {
			items = append(items, prompts.RelayLaterItem{FromID: m.From, FromTitle: m.FromTitle, At: m.At.In(loc).Format("01-02 15:04"), Text: m.Text})
		}
		if len(items) > 0 {
			carry.Text = prompts.RenderRelayLater(prompts.RelayLater{Language: language, Items: items})
		}
		if once != "" {
			if b.st.LaterSends == nil {
				b.st.LaterSends = map[string]relayLaterSend{}
			}
			b.st.LaterSends[key] = carry
			if err := s.saveRelayLocked(); err != nil {
				b.loaded = false
				b.mu.Unlock()
				return "", func(bool) {}, err // never send a payload whose carry reservation was not saved
			}
		}
	}
	b.mu.Unlock()
	return carry.Text, func(taken bool) {
		if !taken {
			return
		}
		s.dropRelayLater(context.WithoutCancel(ctx), to, carry.Items)
	}, nil
}

// dropRelayLater removes exactly items from relayState.Later[to], saves, and publishes an updated snapshot so clients
// drop cleared quiet relays from queuedRelays immediately.
func (s *Service) dropRelayLater(ctx context.Context, to string, items []relayMsg) bool {
	if len(items) == 0 {
		return false
	}
	b := &s.relays
	b.mu.Lock()
	if !s.relayLocked(ctx) {
		b.mu.Unlock()
		return false
	}
	q := b.st.Later[to]
	rest := slices.DeleteFunc(slices.Clone(q), func(m relayMsg) bool { return slices.Contains(items, m) })
	if len(rest) == len(q) {
		b.mu.Unlock()
		return false
	}
	if len(rest) == 0 {
		delete(b.st.Later, to)
	} else {
		b.st.Later[to] = rest
	}
	if err := s.saveRelayLocked(); err != nil {
		b.loaded = false
	}
	b.mu.Unlock()
	if s.hub != nil {
		s.publish(context.WithoutCancel(ctx))
	}
	return true
}

// managedAssistantConversation reports whether sid on machine is a managed assistant conversation (main chat or a
// known headless assistant conversation, excluding guest rooms and ordinary Agent sessions).
func (s *Service) managedAssistantConversation(ctx context.Context, machine, sid string) bool {
	if sid == "" || s.isGuest(ctx, machine, sid) {
		return false
	}
	cfg := s.settings(ctx)
	if cfg.Main != "" && cfg.Main == sid && (machine == cfg.mainRouteMachine() || s.sameMachine(machine, cfg.mainRouteMachine())) {
		return true
	}
	c, known := s.session(ctx, machine, sid)
	return known && c.Assistant && c.PaneID == ""
}

// canCarryOwnerRelayLater reports whether owner send params p represent a turn that reads messages (non-empty user
// text or images, not a slash command, not a meeting batch, and not a public channel turn).
func canCarryOwnerRelayLater(p map[string]any) bool {
	if p == nil || meetingSource(p) || p["public"] == true {
		return false
	}
	text, _ := p["text"].(string)
	trimmed := strings.TrimSpace(text)
	if strings.HasPrefix(trimmed, "/") {
		return false
	}
	if trimmed != "" {
		return true
	}
	switch imgs := p["images"].(type) {
	case []any:
		return len(imgs) > 0
	case []proto.Image:
		return len(imgs) > 0
	}
	return false
}

// withOwnerRelayLater reserves and prepends quiet messages kept in relayState.Later for an owner send to a managed
// assistant conversation (#12867). Retries with the same clientMsgID reuse the original reservation so quiet messages
// that arrive later are never consumed by a retry.
func (s *Service) withOwnerRelayLater(ctx context.Context, machine, sid, clientMsgID string, p map[string]any) (func(bool), error) {
	if !s.managedAssistantConversation(ctx, machine, sid) || !canCarryOwnerRelayLater(p) {
		return func(bool) {}, nil
	}
	later, done, err := s.relayLaterSendFor(ctx, sid, relayLaterOwnerOnce(clientMsgID))
	if err != nil || later == "" {
		return done, err
	}
	if text, _ := p["text"].(string); text != "" {
		p["text"] = later + "\n" + text
	} else {
		p["text"] = later
	}
	s.humanSent.markReplyOnly(sid, clientMsgID)
	return done, nil
}

// pruneOwnerLaterLocked drops expired in-flight owner sends for sid; b.mu is held.
func (s *Service) pruneOwnerLaterLocked(sid string, now time.Time) {
	q := s.relays.ownerLater[sid]
	if len(q) == 0 {
		return
	}
	i := 0
	for i < len(q) && now.Sub(q[i].at) >= ownerLaterWait {
		i++
	}
	if i == 0 {
		return
	}
	q = slices.Clone(q[i:])
	if len(q) == 0 {
		delete(s.relays.ownerLater, sid)
	} else {
		s.relays.ownerLater[sid] = q
	}
}

// relayLaterOwnerSent records an in-flight hub owner send after assistant.send_params succeeds so an unnamed send (or
// an older hub's unnamed session.assistant_sent notice) settles its reserved quiet relays.
func (s *Service) relayLaterOwnerSent(sid, clientMsgID string, done func(bool)) {
	if sid == "" || done == nil {
		return
	}
	b := &s.relays
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	s.pruneOwnerLaterLocked(sid, now)
	if b.ownerLater == nil {
		b.ownerLater = map[string][]ownerLaterSend{}
	}
	q := b.ownerLater[sid]
	if clientMsgID != "" {
		if i := slices.IndexFunc(q, func(o ownerLaterSend) bool { return o.msg == clientMsgID }); i >= 0 {
			o := q[i]
			o.done, o.at = done, now
			b.ownerLater[sid] = append(slices.Delete(q, i, i+1), o)
			return
		}
	}
	q = append(q, ownerLaterSend{msg: clientMsgID, done: done, at: now})
	if len(q) > ownerLaterInFlightMost {
		q = q[len(q)-ownerLaterInFlightMost:]
	}
	b.ownerLater[sid] = q
}

// relayLaterOwnerOutcome settles quiet relays reserved for an owner send when session.assistant_sent (or undelivered
// landed detection) reports its outcome (#12867). Only confirmed acceptance (delivered) drops the reserved items; failed
// or unknown outcomes keep them in relayState.Later. Named sends settle from durable LaterSends so settlement survives
// a manager restart between assistant.send_params and session.assistant_sent.
func (s *Service) relayLaterOwnerOutcome(ctx context.Context, sid, clientMsgID string, delivered bool) {
	if sid == "" {
		return
	}
	b := &s.relays
	b.mu.Lock()
	conn := hubConnOf(ctx)
	if clientMsgID != "" {
		b.ownerLaterNamedOn = max(b.ownerLaterNamedOn, conn+1)
	}
	s.pruneOwnerLaterLocked(sid, time.Now())
	var done func(bool)
	if q := b.ownerLater[sid]; len(q) > 0 {
		i := slices.IndexFunc(q, func(o ownerLaterSend) bool { return o.msg == clientMsgID })
		if i < 0 && clientMsgID == "" && b.ownerLaterNamedOn != conn+1 {
			i = 0
		}
		if i >= 0 {
			done = q[i].done
			q = slices.Delete(q, i, i+1)
			if len(q) == 0 {
				delete(b.ownerLater, sid)
			} else {
				b.ownerLater[sid] = q
			}
		}
	}
	if !delivered {
		b.mu.Unlock()
		return
	}
	if clientMsgID != "" {
		var items []relayMsg
		if s.relayLocked(context.WithoutCancel(ctx)) {
			if carry, ok := b.st.LaterSends[relayLaterKey(sid, relayLaterOwnerOnce(clientMsgID))]; ok {
				items = slices.Clone(carry.Items)
			}
		}
		b.mu.Unlock()
		if len(items) > 0 {
			s.dropRelayLater(context.WithoutCancel(ctx), sid, items)
		}
		return
	}
	b.mu.Unlock()
	if done != nil {
		done(true)
	}
}

// withRelayLater puts what is kept for the receiver of an assistant.send in front of its text (params p), answering
// what to call with whether the send was taken. A meeting's batch and a command (a text starting with "/") carry
// nothing: neither is a turn that reads messages.
func (s *Service) withRelayLater(ctx context.Context, p map[string]any) (func(taken bool), error) {
	id, _ := p["session_id"].(string)
	text, ok := p["text"].(string)
	if id == "" || !ok || meetingSource(p) || strings.HasPrefix(strings.TrimSpace(text), "/") {
		return func(bool) {}, nil
	}
	once, _ := p["once_id"].(string)
	later, done, err := s.relayLaterSendFor(ctx, id, once)
	if err != nil || later == "" {
		return done, err
	}
	p["text"] = later + "\n" + text
	return done, nil
}

// pruneRelayLaterLocked lets go of kept messages older than relayLaterKeep; b.mu is held.
func (s *Service) pruneRelayLaterLocked(now time.Time) {
	for key, sent := range s.relays.st.LaterSends {
		if now.Sub(sent.At) > relayLaterKeep {
			delete(s.relays.st.LaterSends, key)
		}
	}
	for id, q := range s.relays.st.Later {
		q = slices.DeleteFunc(q, func(m relayMsg) bool { return now.Sub(m.QueuedAt) > relayLaterKeep })
		if len(q) == 0 {
			delete(s.relays.st.Later, id)
		} else {
			s.relays.st.Later[id] = q
		}
	}
}
