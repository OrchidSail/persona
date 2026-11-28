package assistant

import (
	"cmp"
	"context"
	"time"

	"github.com/nwverse/persona/server/internal/channel"
)

// threadBindingRef carries the route selected at admission and the version used for delivery. The channel
// generation fences unbinds. The topic generation fences resets of that topic and survives a manager restart.
type threadBindingRef struct {
	channel           string
	topic             channelTopicID
	channelGeneration uint64
	generation        uint64
	idleTTL           time.Duration
}

func (r threadBindingRef) isThread() bool { return r.topic.present() }

func (c *channelsBook) threadTopic(r threadBindingRef) (channelTopic, bool) {
	if p := c.prefs[r.channel]; p != nil && p.ThreadTopics != nil {
		topic, ok := p.ThreadTopics.Entities[r.topic]
		return topic, ok
	}
	return channelTopic{}, false
}

func (c *channelsBook) bindingCurrent(r threadBindingRef) bool {
	if c.bindings.generation(r.channel) != r.channelGeneration {
		return false
	}
	if !r.isThread() {
		return true
	}
	topic, ok := c.threadTopic(r)
	return ok && topic.Generation == r.generation
}

func (s *Service) threadBindingCurrent(r threadBindingRef) bool {
	s.chans.mu.Lock()
	defer s.chans.mu.Unlock()
	return s.chans.bindingCurrent(r)
}

func (s *Service) resolveThreadBindingLocked(ctx context.Context, id string, m channel.Message, v channel.View, v2 bool) (threadBindingRef, error) {
	c := &s.chans
	s.loadChannelsLocked(ctx)
	if !c.loaded {
		return threadBindingRef{}, errChannelsUnread
	}
	p := c.prefsForUpdate(id)
	ref := threadBindingRef{channel: id, channelGeneration: c.bindings.generation(id), idleTTL: channelThreadIdleTTL(v2, &p)}
	keys := threadRouting(v, p.ThreadMode, v2).keys(m)
	if keys.primary == "" {
		return ref, nil
	}
	return s.resolveThreadTopicLocked(ref, p, keys)
}

// resolveThreadTopicLocked commits newly observed aliases before acknowledging the message. Single-key messages
// can then reserve the same queue even while the first delivery is waiting for the node.
func (s *Service) resolveThreadTopicLocked(ref threadBindingRef, p channelPrefs, keys threadRouteKeys) (threadBindingRef, error) {
	c := &s.chans
	keys = p.ThreadTopics.matchingKeys(keys)
	var primary, alias channelTopicID
	if p.ThreadTopics != nil {
		primary, alias = p.ThreadTopics.Aliases[keys.primary], p.ThreadTopics.Aliases[keys.alias]
	}
	ref.topic = cmp.Or(primary, alias)
	if topic, ok := c.threadTopic(ref); ok && primary.present() && (keys.alias == "" || alias == primary) &&
		(keys.topic == "" || keys.topic == topic.StructuredKey) {
		ref.generation = topic.Generation
		return ref, nil
	}
	joining := primary.present() && alias.present() && primary != alias
	if joining && (c.topicQueued(ref.channel, primary) || c.topicQueued(ref.channel, alias)) {
		// Old bindings can already have independent deliveries. Let them settle before folding their identities.
		return threadBindingRef{}, errChannelTopicsBusy
	}

	now := c.clock().UTC()
	p.ThreadTopics = p.ThreadTopics.clone()
	if !ref.topic.present() {
		ref.topic = newChannelTopicID()
		p.ThreadTopics.Entities[ref.topic] = channelTopic{Generation: 1, Binding: channelThread{At: now}}
	}
	if joining {
		p.ThreadTopics.join(primary, alias, now, ref.idleTTL)
	}
	topic := p.ThreadTopics.Entities[ref.topic]
	if keys.topic != "" {
		topic.StructuredKey = keys.topic
	}
	p.ThreadTopics.Entities[ref.topic] = topic
	p.ThreadTopics.Aliases[keys.primary] = ref.topic
	if keys.alias != "" {
		p.ThreadTopics.Aliases[keys.alias] = ref.topic
	}
	c.pruneThreadTopics(ref, p, now)
	if len(p.ThreadTopics.Aliases) > channelThreadsKeep {
		return threadBindingRef{}, errChannelTopicsBusy
	}
	if err := s.saveThreadTopicsLocked(ref.channel, p); err != nil {
		return threadBindingRef{}, err
	}
	ref.generation = topic.Generation
	return ref, nil
}

func channelThreadIdleTTL(v2 bool, p *channelPrefs) time.Duration {
	if v2 && p.ThreadIdleHours > 0 {
		return time.Duration(min(p.ThreadIdleHours, channel.MaxThreadIdleHours)) * time.Hour
	}
	return channelThreadsKeepFor
}

func (s *Service) lookupThreadBinding(r threadBindingRef) *channelThread {
	c := &s.chans
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.bindingCurrent(r) {
		return nil
	}
	if topic, ok := c.threadTopic(r); ok && topic.Binding.active(c.clock(), r.idleTTL) {
		return &topic.Binding
	}
	return nil
}

// refreshThreadBinding records a completed delivery only against the generation that sent it. A storage failure
// leaves the prior binding available, and the caller still follows the turn that was delivered.
func (s *Service) refreshThreadBinding(r threadBindingRef, machine, session string) bool {
	c := &s.chans
	c.mu.Lock()
	defer c.mu.Unlock()
	topic, ok := c.threadTopic(r)
	if !ok || !c.bindingCurrent(r) {
		return false
	}
	p := c.prefsForUpdate(r.channel)
	p.ThreadTopics = p.ThreadTopics.clone()
	now := c.clock().UTC()
	topic.Binding = channelThread{MachineID: machine, SessionID: session, At: now}
	p.ThreadTopics.Entities[r.topic] = topic
	c.pruneThreadTopics(r, p, now)
	_ = s.saveThreadTopicsLocked(r.channel, p)
	return true
}

// resetThreadBinding advances the topic's generation and clears its conversation in one write. It hands the
// updated reference back to /new so any trailing text starts the new generation.
func (s *Service) resetThreadBinding(r *threadBindingRef) error {
	c := &s.chans
	c.mu.Lock()
	defer c.mu.Unlock()
	topic, ok := c.threadTopic(*r)
	if !ok || !c.bindingCurrent(*r) {
		return nil
	}
	p := c.prefsForUpdate(r.channel)
	p.ThreadTopics = p.ThreadTopics.clone()
	topic.Generation++
	topic.Binding = channelThread{At: c.clock().UTC()}
	p.ThreadTopics.Entities[r.topic] = topic
	if err := s.saveThreadTopicsLocked(r.channel, p); err != nil {
		return err
	}
	r.generation = topic.Generation
	return nil
}

func (s *Service) saveThreadTopicsLocked(id string, p channelPrefs) error {
	p.Threads = p.ThreadTopics.legacyBindings()
	return s.saveChannelsLocked(id, p)
}

func (c *channelsBook) pruneThreadTopics(r threadBindingRef, p channelPrefs, now time.Time) {
	keepFor := max(channelThreadsKeepFor, channelThreadIdleTTL(true, &p))
	p.ThreadTopics.prune(now, keepFor, func(id channelTopicID) bool {
		return id == r.topic || c.topicQueued(r.channel, id)
	})
}
