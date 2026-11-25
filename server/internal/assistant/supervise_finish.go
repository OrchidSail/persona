package assistant

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/nwverse/persona/server/internal/proto"
	"github.com/nwverse/persona/server/internal/transcript"
)

// promoteSupervision is monotone within a wake-up: a later silent finish cannot hide a user action.
func (s *Service) promoteSupervision(self string) {
	key := s.supervisionHoldKey(context.Background(), self)
	b := &s.supervise
	b.mu.Lock()
	defer b.mu.Unlock()
	b.init()
	if _, held := b.hold[key]; held {
		b.visible[key] = true
		return
	}
	if p, ok := b.preempted[key]; ok {
		p.promoted = true
		b.preempted[key] = p
	}
}

// quietInternalAcceptedReview uses only the current plan binding and supervision
// ledger. Unknown provenance fails open as user-visible; titles never participate.
func (s *Service) quietInternalAcceptedReview(ctx context.Context, todoID string, userCheck bool) bool {
	if userCheck {
		return false
	}
	t, ok := s.todoNow(todoID)
	if !ok || t.Supervision == nil {
		return false
	}
	plan, index, err := s.currentPlanAssignment(ctx, t)
	if err != nil || plan == nil || index < 0 || index >= len(plan.Tasks) {
		return false
	}
	task := plan.Tasks[index]
	if task.UserRequested {
		return false
	}
	if task.Parent != "" || t.Supervision.Round > 0 {
		return true
	}
	review := t.Supervision.AutoReview
	return review != nil && review.Round == t.Supervision.Round && (review.State == autoClean || review.State == autoBlocking)
}

// toolSupervisionFinish records the caller's disposition in its persisted MCP result.
// The transcript remains intact; clients fold the entire triage turn, not just its input.
func (s *Service) toolSupervisionFinish(ctx context.Context, self string, args json.RawMessage) (string, error) {
	return s.toolSupervisionFinishOn(ctx, "", self, args)
}

func (s *Service) toolSupervisionFinishOn(ctx context.Context, machine, self string, args json.RawMessage) (string, error) {
	var a struct {
		Visibility string `json:"visibility"`
		Summary    string `json:"summary"`
	}
	if err := json.Unmarshal(args, &a); err != nil {
		return "", err
	}
	a.Summary = strings.TrimSpace(a.Summary)
	if (a.Visibility != "silent" && a.Visibility != "user") || a.Summary == "" || utf8.RuneCountInString(a.Summary) > 1000 {
		return "", errors.New("visibility must be silent or user, with a summary of 1–1000 characters")
	}
	const notSupervisionErr = "this is not an unpromoted supervision turn; normal user messages cannot be hidden"
	key := s.supervisionHoldKey(ctx, self)
	b := &s.supervise
	b.mu.Lock()
	b.init()
	if _, held := b.hold[key]; held {
		if a.Visibility == "silent" && b.visible[key] {
			b.mu.Unlock()
			return "", errors.New(notSupervisionErr)
		}
		level := "triage"
		if a.Visibility == "user" {
			level = "user"
			b.visible[key] = true
		}
		b.mu.Unlock()
		body, err := json.Marshal(map[string]string{"visibility": a.Visibility, "summary": a.Summary, "level": level})
		return string(body), err
	}
	_, hasPreempted := b.preempted[key]
	b.mu.Unlock()

	// Fallback when the in-memory push hold was cleared (owner queued a message mid-turn,
	// side-supervisor user-level push release, late agy transcript confirmation, or manager
	// restart): inspect the live transcript's latest input round and fail closed if a
	// non-supervision, closed, or pre-owner-input stale round is observed.
	observed, supervision, promoted, startedAt := s.verifyActiveSupervisionTurn(ctx, machine, self)
	if observed && !supervision {
		if hasPreempted {
			b.mu.Lock()
			delete(b.preempted, key)
			b.mu.Unlock()
		}
		return "", errors.New(notSupervisionErr)
	}
	if supervision {
		active := s.supervisionRoundActive(key) || (machine != "" && s.seenWorking(machine, self))
		b.mu.Lock()
		b.init()
		p, stillPreempted := b.preempted[key]
		// Without an active preempted round, a supervision item older than the owner's latest message
		// cannot authorize hiding a subsequent owner turn on runtimes (like agy) that omit turn_duration.
		if !(stillPreempted && active) {
			if spoke := b.lastUserSpoke[key]; !spoke.IsZero() && (startedAt.IsZero() || !startedAt.After(spoke)) {
				b.mu.Unlock()
				return "", errors.New(notSupervisionErr)
			}
		}
		if a.Visibility == "silent" && (promoted || b.visible[key] || p.promoted) {
			b.mu.Unlock()
			return "", errors.New(notSupervisionErr)
		}
		level := "triage"
		if a.Visibility == "user" {
			level = "user"
			b.visible[key] = true
			if stillPreempted {
				p.promoted = true
				b.preempted[key] = p
			}
		} else {
			delete(b.sideLinks, key)
		}
		b.mu.Unlock()
		body, err := json.Marshal(map[string]string{"visibility": a.Visibility, "summary": a.Summary, "level": level})
		return string(body), err
	}

	if hasPreempted {
		active := s.supervisionRoundActive(key) || (machine != "" && s.seenWorking(machine, self))
		b.mu.Lock()
		p, still := b.preempted[key]
		if still && active {
			if a.Visibility == "silent" && (p.promoted || b.visible[key]) {
				b.mu.Unlock()
				return "", errors.New(notSupervisionErr)
			}
			level := "triage"
			if a.Visibility == "user" {
				level = "user"
				p.promoted = true
				b.preempted[key] = p
				b.visible[key] = true
			} else {
				delete(b.sideLinks, key)
			}
			b.mu.Unlock()
			body, err := json.Marshal(map[string]string{"visibility": a.Visibility, "summary": a.Summary, "level": level})
			return string(body), err
		}
		if still && !active {
			delete(b.preempted, key)
		}
		b.mu.Unlock()
	}

	return "", errors.New(notSupervisionErr)
}

func (s *Service) verifyActiveSupervisionTurn(ctx context.Context, machine, self string) (observed, supervision, promoted bool, startedAt time.Time) {
	if self == "" || s.hub == nil {
		return false, false, false, time.Time{}
	}
	if machine == "" {
		machine = s.settings(ctx).MachineID
	}
	if machine == "" {
		return false, false, false, time.Time{}
	}
	readCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var res struct {
		Items []proto.Item `json:"items"`
	}
	if err := s.call(readCtx, machine, DeviceSessionItems, map[string]any{"session_id": self, "turns": 2, "lazy_images": true}, &res); err != nil {
		return false, false, false, time.Time{}
	}
	return transcript.ActiveSupervisionRound(res.Items)
}
