package assistant

import (
	"strconv"
	"strings"

	"github.com/nwverse/persona/server/internal/channel"
)

// channelThreadRouting selects a route from the channel's saved choice and the message's available identifiers.
// An empty mode keeps the plugin's established thread routes, including groups with only an opaque thread key.
// A complete topic pair announces structured topic routing for a thread-capable plugin.
type channelThreadRouting struct {
	mode          channel.ThreadMode
	legacyThreads bool
	structured    bool
}

func threadRouting(v channel.View, configured channel.ThreadMode, integrationsV2 bool) channelThreadRouting {
	if !integrationsV2 {
		configured = ""
	}
	return channelThreadRouting{mode: configured, legacyThreads: v.Capabilities.Threads, structured: integrationsV2}
}

type threadRouteKeys struct {
	primary, alias string
	topic          string
}

func (r channelThreadRouting) keys(m channel.Message) threadRouteKeys {
	legacy := strings.TrimSpace(m.Thread)
	chatID, topicID := strings.TrimSpace(m.ChatID), strings.TrimSpace(m.TopicID)
	kind := m.ChatKind.Known()
	var topic string
	if kind != channel.ChatKindDirect && chatID != "" && topicID != "" {
		topic = channelTopicRouteKey(chatID, topicID)
	}
	var primary string
	switch r.mode {
	case "":
		if r.legacyThreads {
			primary = legacy
			if r.structured && topic != "" {
				primary = topic
			}
		}
	case channel.ThreadModePerGroupAndTopic, channel.ThreadModePerTopic:
		switch {
		case kind == channel.ChatKindDirect:
		case topic != "":
			primary = topic
		case kind == channel.ChatKindGroup && topicID == "":
			if r.mode == channel.ThreadModePerGroupAndTopic && chatID != "" {
				primary = channelGroupRouteKey(chatID)
			}
		default:
			primary = legacy
		}
	}
	keys := threadRouteKeys{primary: primary}
	if legacy != "" && topic != "" && legacy != topic {
		switch primary {
		case legacy:
			keys.alias = topic
		case topic:
			keys.alias = legacy
		}
	}
	if keys.primary == topic || keys.alias == topic {
		keys.topic = topic
	}
	return keys
}

// The length prefix keeps chat and topic identifiers unambiguous even when they contain colons.
func channelTopicRouteKey(chatID, topicID string) string {
	return "v2:topic:" + strconv.Itoa(len(chatID)) + ":" + chatID + ":" + topicID
}

func channelGroupRouteKey(chatID string) string { return "v2:group:" + chatID }
