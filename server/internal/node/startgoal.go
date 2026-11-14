package node

import (
	"context"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/screen"
)

// A start's goal (session.start's goal, nodeproto.StartGoal; docs/integrations/agent-goal.md "A goal set at the
// start"): Claude Code's `/goal <condition>`, typed into a new Claude before its first message, so that the agent checks
// the condition each time it would stop. /goal is a local command that answers at once; typed in the same message as
// the first prompt it would take the prompt for its condition and the agent would not start on the work. So it goes
// on its own, first, and the first message is typed only once the box is known to be clear again, as after ultracode
// (ultracode.go startUltracode). A goal never fails a start: Claude is up, and the first message goes in either way.

// goalVerdictWait bounds how long the screen has to show what Claude Code said to /goal; tests shorten it.
var goalVerdictWait = 5 * time.Second

const goalVerdictPoll = 200 * time.Millisecond

// Why a start's goal cannot be had: refused before anything starts, in the words the node's other start refusals use
// (ultracode.go), which the caller may put on the task.
const (
	goalNotClaude  = "只有新起的 Claude Code Agent 能在第一条消息前设目标"
	goalResumed    = "恢复的会话不在第一条消息前设目标（会覆盖它原来的目标）"
	goalTooLong    = "目标太长，没有设 /goal"
	goalScreenless = "这个会话所在的终端不能读屏或按键，没法设 /goal"
)

// What Claude Code said to a start's /goal when it did not take, for the node's log.
const (
	goalUntrusted    = "the workspace is not trusted"
	goalHooksOff     = "hooks are restricted (disableAllHooks or allowManagedHooksOnly)"
	goalPolicyUnread = "the organization's settings were not read yet"
	goalOverLong     = "the condition is too long"
	goalEmpty        = "the condition is empty"
	goalUnknown      = "this Claude Code has no /goal"
)

func goalCommand(goal string) string { return "/goal " + goal }

// cleanStartGoal is a start's goal on one line, "" for none: a newline would submit the command with only its first
// line.
func cleanStartGoal(goal string) string { return strings.Join(strings.Fields(goal), " ") }

// checkStartGoal refuses, before anything starts, a start whose goal (cleaned) cannot be had: kind is the agent the
// start runs ("claude", or what the launcher, a runtime or an app makes it), resume the session it resumes.
func (n *Node) checkStartGoal(goal, kind, resume string) error {
	switch {
	case goal == "":
		return nil
	case utf8.RuneCountInString(goal) > proto.StartGoalMax:
		return refusal(proto.CodeStartGoalUnsupported, goalTooLong, nil)
	case resume != "":
		return refusal(proto.CodeStartGoalUnsupported, goalResumed, nil)
	case kind != "claude":
		return refusal(proto.CodeStartGoalUnsupported, goalNotClaude, nil)
	}
	if h := n.defaultHost(); h != nil && !drivesScreen(h) {
		return refusal(proto.CodeStartGoalUnsupported, goalScreenless, nil)
	}
	return nil
}

// goalOutcome reads what Claude Code said to a /goal typed at a screen that showed before: set, or why not; both
// zero while it has said nothing yet. Only what is new since before counts, never an earlier goal's line above it.
// Lines are joined first: a long condition wraps, and "Goal set:" may be split across two lines.
func goalOutcome(before, shown string) (set bool, note string) {
	flat := strings.Join(strings.Fields(screen.Plain(shown)), " ")
	prev := strings.Join(strings.Fields(screen.Plain(before)), " ")
	newer := func(s string) bool { return strings.Count(flat, s) > strings.Count(prev, s) }
	switch {
	case newer("Goal set: "):
		return true, ""
	case newer("/goal is only available in trusted workspaces"):
		return false, goalUntrusted
	case newer("/goal can't run while hooks are restricted"):
		return false, goalHooksOff
	case newer("/goal can't run yet"):
		return false, goalPolicyUnread
	case newer("Goal condition is limited to"), newer("The goal condition exceeds"):
		return false, goalOverLong
	case newer("The goal condition is empty"):
		return false, goalEmpty
	case newer("Unknown command: /goal"):
		return false, goalUnknown
	}
	return false, ""
}

// startGoal types /goal <goal> into the Claude just started in pane and reads Claude Code's answer off the screen. It
// is the best it can do and never fails the start; what it saw is logged. unsure: the command went to the terminal
// and is not known to have gone in, so it may still sit in the input box (the caller looks before typing more).
func (n *Node) startGoal(ctx context.Context, id, pane, goal string) (unsure bool) {
	before, err := n.readPane(ctx, pane, "visible", 60)
	if err != nil { // its answer could not be read: better no goal than a first message typed after a stuck command
		n.logf("start %s: goal not set: the screen cannot be read: %v", id, err)
		return false
	}
	cmd := goalCommand(goal)
	if err := n.prompt(ctx, pane, cmd); err != nil {
		n.logf("start %s: goal not typed: %v", id, err)
		return deliveryRefusal(err) == nil // a refusal typed nothing; any other error may have typed it
	}
	n.typing.typed(pane, cmd)
	for deadline := time.Now().Add(goalVerdictWait); time.Now().Before(deadline); {
		select {
		case <-ctx.Done():
			n.logf("start %s: no answer to /goal on the screen: %v", id, ctx.Err())
			return true
		case <-time.After(goalVerdictPoll):
		}
		shown, err := n.readPane(ctx, pane, "visible", 60)
		if err != nil || shown == before {
			continue
		}
		if set, note := goalOutcome(before, shown); set {
			n.logf("start %s: goal set", id)
			return false
		} else if note != "" {
			n.logf("start %s: goal not set: %s", id, note)
			return false
		}
	}
	n.logf("start %s: no answer to /goal on the screen", id)
	return true
}
