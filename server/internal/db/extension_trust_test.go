package db

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"
)

// Extension trust (#13246): an extension's trust and its granted categories, the grants replaced as a whole.
func TestExtensionTrust(t *testing.T) {
	d, ctx := open(t), context.Background()
	got := func() map[string]ExtensionTrust {
		t.Helper()
		all, err := d.ExtensionTrusts(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return all
	}
	if all := got(); len(all) != 0 {
		t.Fatalf("a new store: %v", all)
	}
	for _, step := range []struct {
		ext, trust string
		cats       []string
	}{
		{"qa-memory", "owner", nil},
		{"qa-other", "third_party", []string{"finance", "health"}},
		{"qa-other", "third_party", []string{"health"}},
	} {
		if err := d.SetExtensionTrust(ctx, step.ext, step.trust, step.cats, "user"); err != nil {
			t.Fatal(err)
		}
	}
	all := got()
	if m := all["qa-memory"]; m.Trust != "owner" || len(m.Categories) != 0 {
		t.Errorf("qa-memory: %+v", m)
	}
	if o := all["qa-other"]; o.Trust != "third_party" || !slices.Equal(o.Categories, []string{"health"}) {
		t.Errorf("qa-other: %+v", o)
	}
	if err := d.SetExtensionTrust(ctx, "qa-other", "third_party", nil, "user"); err != nil {
		t.Fatal(err)
	}
	if o := got()["qa-other"]; len(o.Categories) != 0 {
		t.Errorf("grants taken away: %+v", o)
	}
	if err := d.ForgetExtensionTrust(ctx, "qa-memory"); err != nil {
		t.Fatal(err)
	}
	if _, ok := got()["qa-memory"]; ok {
		t.Error("a removed extension's trust is kept")
	}
	if err := d.SetExtensionTrust(ctx, "", "owner", nil, "user"); err == nil {
		t.Error("no extension")
	}
}

func TestExtensionTrustConcurrentReplacements(t *testing.T) {
	first, second := open(t), connectTestDB(t)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	const id = "example-extension"
	base, err := first.ReplaceExtensionTrust(ctx, id, "third_party", []string{"health", "finance"}, "user", nil)
	if err != nil {
		t.Fatal(err)
	}
	got, err := second.ExtensionTrusts(ctx)
	if err != nil || got[id].Revision != base.Revision || base.Revision == DefaultExtensionTrustRevision {
		t.Fatalf("second connection did not read the stored revision: %+v, %v", got, err)
	}

	type result struct {
		state ExtensionTrust
		err   error
	}
	start := make(chan struct{})
	results := make(chan result, 2)
	for i, conn := range []*DB{first, second} {
		go func() {
			<-start
			state, err := conn.ReplaceExtensionTrust(ctx, id, "third_party", base.Categories[i:i+1], "user", &base.Revision)
			results <- result{state, err}
		}()
	}
	close(start)
	winner, conflict := <-results, <-results
	if winner.err != nil {
		winner, conflict = conflict, winner
	}
	if winner.err != nil || !errors.Is(conflict.err, ErrExtensionTrustMoved) {
		t.Fatalf("expected one replacement to win: %+v, %+v", winner, conflict)
	}
	if winner.state.Revision == base.Revision || conflict.state.Revision != winner.state.Revision ||
		len(winner.state.Categories) != 1 || !slices.Equal(conflict.state.Categories, winner.state.Categories) {
		t.Fatalf("conflict did not return the winning grant: %+v, %+v", winner, conflict)
	}
	got, err = second.ExtensionTrusts(ctx)
	if err != nil || got[id].Revision != winner.state.Revision || !slices.Equal(got[id].Categories, winner.state.Categories) {
		t.Fatalf("a stale edit changed the grant: %+v, %v", got, err)
	}
}
