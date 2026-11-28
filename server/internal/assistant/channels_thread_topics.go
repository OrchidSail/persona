package assistant

import (
	"cmp"
	"errors"
	"maps"
	"slices"
	"strings"
	"time"
)

type channelTopicID struct{ value string }

func newChannelTopicID() channelTopicID { return channelTopicID{value: randomHex(16)} }

func (id channelTopicID) present() bool { return id.value != "" }

func (id channelTopicID) MarshalText() ([]byte, error) { return []byte(id.value), nil }

func (id *channelTopicID) UnmarshalText(text []byte) error {
	id.value = string(text)
	return nil
}

// channelThread is also the binding row understood by managers from before topic identities.
type channelThread struct {
	MachineID string    `json:"machine_id"`
	SessionID string    `json:"session_id"`
	At        time.Time `json:"at"`
}

func (b channelThread) active(now time.Time, ttl time.Duration) bool {
	return b.SessionID != "" && now.Sub(b.At) <= ttl
}

type channelTopic struct {
	Generation    uint64        `json:"generation"`
	Binding       channelThread `json:"binding"`
	StructuredKey string        `json:"structured_key,omitempty"`
}

func (t channelTopic) reset() bool { return t.Generation > 1 && t.Binding.SessionID == "" }

// channelThreadTopics stores each binding once. Alias values refer to stable IDs that also identify queues.
type channelThreadTopics struct {
	Entities map[channelTopicID]channelTopic `json:"entities"`
	Aliases  map[string]channelTopicID       `json:"aliases"`
}

func newChannelThreadTopics() *channelThreadTopics {
	return &channelThreadTopics{Entities: map[channelTopicID]channelTopic{}, Aliases: map[string]channelTopicID{}}
}

func (b *channelThreadTopics) clone() *channelThreadTopics {
	next := newChannelThreadTopics()
	if b != nil {
		maps.Copy(next.Entities, b.Entities)
		maps.Copy(next.Aliases, b.Aliases)
	}
	return next
}

// restoreThreadTopics imports rows left by older managers. An older manager refreshed both known aliases to
// the same conversation, so those rows already supply evidence of shared identity.
func restoreThreadTopics(rows map[string]*channelThread) *channelThreadTopics {
	b := newChannelThreadTopics()
	byConversation := make(map[[2]string]channelTopicID)
	for _, key := range slices.Sorted(maps.Keys(rows)) {
		binding := rows[key]
		if key == "" || binding == nil || binding.SessionID == "" {
			continue
		}
		conversation := [2]string{binding.MachineID, binding.SessionID}
		id := byConversation[conversation]
		if !id.present() {
			id = newChannelTopicID()
			byConversation[conversation] = id
			b.Entities[id] = channelTopic{Generation: 1, Binding: *binding}
		}
		topic := b.Entities[id]
		if binding.At.After(topic.Binding.At) {
			topic.Binding = *binding
		}
		if strings.HasPrefix(key, "v2:topic:") {
			topic.StructuredKey = key
		}
		b.Entities[id] = topic
		b.Aliases[key] = id
	}
	return b
}

// matchingKeys protects a different structured topic from an opaque key that the producer has reused.
func (b *channelThreadTopics) matchingKeys(keys threadRouteKeys) threadRouteKeys {
	if keys.alias == "" || b == nil {
		return keys
	}
	legacy := keys.primary
	if legacy == keys.topic {
		legacy = keys.alias
	}
	if known := b.Entities[b.Aliases[legacy]].StructuredKey; known != "" && known != keys.topic {
		keys.alias = ""
		if keys.primary == legacy {
			keys.topic = ""
		}
	}
	return keys
}

// join folds two existing bindings after both queues have drained. The primary's live binding wins, with the
// same idle fallback used for older split rows. A reset is retained when it is newer than the competing binding.
func (b *channelThreadTopics) join(primary, alias channelTopicID, now time.Time, ttl time.Duration) {
	topic, other := b.Entities[primary], b.Entities[alias]
	if other.reset() && other.Binding.At.After(topic.Binding.At) ||
		!topic.reset() && !topic.Binding.active(now, ttl) && other.Binding.active(now, ttl) {
		topic.Binding = other.Binding
	}
	topic.Generation = max(topic.Generation, other.Generation) + 1
	b.Entities[primary] = topic
	delete(b.Entities, alias)
	for key, id := range b.Aliases {
		if id == alias {
			b.Aliases[key] = primary
		}
	}
}

// legacyBindings is the projection for an older manager reading the channels setting after a downgrade.
func (b *channelThreadTopics) legacyBindings() map[string]*channelThread {
	rows := make(map[string]*channelThread)
	for key, id := range b.Aliases {
		if binding := b.Entities[id].Binding; binding.SessionID != "" {
			rows[key] = &binding
		}
	}
	return rows
}

const (
	channelThreadsKeep    = 500
	channelThreadsKeepFor = 30 * 24 * time.Hour
)

var errChannelTopicsBusy = errors.New("the channel's topic routes are busy")

// prune drops expired topics and then the oldest inactive topics until the routing key budget fits. A queued
// topic keeps all of its aliases and its identity until its delivery finishes.
func (b *channelThreadTopics) prune(now time.Time, keepFor time.Duration, keep func(channelTopicID) bool) {
	maps.DeleteFunc(b.Entities, func(id channelTopicID, topic channelTopic) bool {
		return !keep(id) && now.Sub(topic.Binding.At) > keepFor
	})
	maps.DeleteFunc(b.Aliases, func(_ string, id channelTopicID) bool {
		_, ok := b.Entities[id]
		return !ok
	})
	if len(b.Aliases) <= channelThreadsKeep {
		return
	}
	counts := make(map[channelTopicID]int)
	for _, id := range b.Aliases {
		counts[id]++
	}
	var candidates []channelTopicID
	for id := range b.Entities {
		if !keep(id) {
			candidates = append(candidates, id)
		}
	}
	slices.SortFunc(candidates, func(a, c channelTopicID) int {
		if n := b.Entities[a].Binding.At.Compare(b.Entities[c].Binding.At); n != 0 {
			return n
		}
		return cmp.Compare(a.value, c.value)
	})
	remaining := len(b.Aliases)
	for _, id := range candidates {
		if remaining <= channelThreadsKeep {
			break
		}
		remaining -= counts[id]
		delete(b.Entities, id)
	}
	maps.DeleteFunc(b.Aliases, func(_ string, id channelTopicID) bool {
		_, ok := b.Entities[id]
		return !ok
	})
}
