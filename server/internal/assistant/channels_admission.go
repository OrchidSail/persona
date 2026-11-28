package assistant

import (
	"context"
	"slices"

	"github.com/nwverse/persona/server/internal/channel"
)

type channelAdmission struct {
	binding     threadBindingRef
	reservation threadBindingReservation
}

// admitChannelMessage claims the message, selects its topic and reserves its position under the book lock.
// No node or channel RPC runs under the book lock. Reserve before persisting the claim so a later command cannot
// overtake a durable receipt. A failed write rolls both changes back for redelivery.
func (s *Service) admitChannelMessage(ctx context.Context, id string, m channel.Message) (*channelAdmission, error) {
	v2, err := s.integrationsV2On(ctx)
	if err != nil {
		return nil, err
	}
	v, _, err := s.pluginChannelNow(ctx, id)
	if err != nil {
		return nil, err
	}
	c := &s.chans
	c.mu.Lock()
	defer c.mu.Unlock()
	s.loadChannelStateLocked(ctx)
	if !c.stLoaded {
		return nil, errChannelsUnread
	}
	seen, hadSeen := c.st.Seen[id]
	if m.ID == "" || slices.Contains(seen, m.ID) {
		return nil, nil
	}
	binding, err := s.resolveThreadBindingLocked(ctx, id, m, v, v2)
	if err != nil {
		return nil, err
	}
	reservation := c.bindings.reserve(binding)
	next := append(seen, m.ID)
	if len(next) > channelSeenKeep {
		next = next[len(next)-channelSeenKeep:]
	}
	c.st.Seen[id] = next
	if err := s.saveChannelStateLocked(); err != nil {
		if hadSeen {
			c.st.Seen[id] = seen
		} else {
			delete(c.st.Seen, id)
		}
		c.bindings.rollback(reservation)
		return nil, err
	}
	return &channelAdmission{binding: binding, reservation: reservation}, nil
}

// run takes the topic version at the head of its queue so messages after /new use the new generation. An
// unbound channel starts a new queue and fences work admitted by its previous binding.
func (a *channelAdmission) run(s *Service, m channel.Message, action string) {
	c := &s.chans
	defer func() {
		c.mu.Lock()
		c.bindings.release(a.reservation)
		c.mu.Unlock()
	}()
	a.reservation.wait()
	binding := a.binding
	c.mu.Lock()
	if topic, ok := c.threadTopic(binding); ok {
		binding.generation = topic.Generation
	}
	c.mu.Unlock()
	s.receiveChannelMessage(binding, m, action)
}

// threadBindingIndex owns in-memory queue tails and account generations. The channels book lock protects both.
type threadBindingIndex struct {
	generations map[string]uint64
	pending     map[threadBindingQueueKey]chan struct{}
}

func (i *threadBindingIndex) generation(id string) uint64 { return i.generations[id] }

func (i *threadBindingIndex) invalidate(id string) {
	if i.generations == nil {
		i.generations = make(map[string]uint64)
	}
	i.generations[id]++
}

type threadBindingQueueKey struct {
	channel           string
	topic             channelTopicID
	channelGeneration uint64
}

func (c *channelsBook) topicQueued(channel string, topic channelTopicID) bool {
	key := threadBindingQueueKey{channel: channel, topic: topic, channelGeneration: c.bindings.generation(channel)}
	return c.bindings.pending[key] != nil
}

type threadBindingReservation struct {
	key        threadBindingQueueKey
	prev, done chan struct{}
}

func (i *threadBindingIndex) reserve(r threadBindingRef) threadBindingReservation {
	if i.pending == nil {
		i.pending = make(map[threadBindingQueueKey]chan struct{})
	}
	key := threadBindingQueueKey{channel: r.channel, topic: r.topic, channelGeneration: r.channelGeneration}
	q := threadBindingReservation{key: key, prev: i.pending[key], done: make(chan struct{})}
	i.pending[key] = q.done
	return q
}

// rollback runs under the book lock before a later reservation can join this one.
func (i *threadBindingIndex) rollback(q threadBindingReservation) {
	if q.prev == nil {
		delete(i.pending, q.key)
	} else {
		i.pending[q.key] = q.prev
	}
}

func (q threadBindingReservation) wait() {
	if q.prev != nil {
		<-q.prev
	}
}

func (i *threadBindingIndex) release(q threadBindingReservation) {
	if i.pending[q.key] == q.done {
		delete(i.pending, q.key)
	}
	close(q.done)
}
