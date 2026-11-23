package node

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/nwverse/persona/server/internal/proto"
)

func TestSetExtraPathInvalidatesLauncherCacheOnChange(t *testing.T) {
	origPath := os.Getenv("PATH")
	t.Cleanup(func() {
		_ = os.Setenv("PATH", origPath)
		extraPath.mu.Lock()
		extraPath.base, extraPath.dirs = nil, nil
		extraPath.mu.Unlock()
	})
	resetExtraPath(origPath)

	launcherCache.Lock()
	oldAt, oldList, oldGen, oldInv := launcherCache.at, launcherCache.list, launcherCache.generation, launcherCache.invalidated
	launcherCache.at = time.Now()
	launcherCache.list = []proto.Launcher{{Name: "cached"}}
	baseGen, baseInv := launcherCache.generation, launcherCache.invalidated
	launcherCache.Unlock()
	lastPathScan.Lock()
	oldScan := lastPathScan.got
	lastPathScan.got = &pathScan{list: []proto.Launcher{{Name: "path-cached"}}}
	lastPathScan.Unlock()
	t.Cleanup(func() {
		launcherCache.Lock()
		launcherCache.at, launcherCache.list, launcherCache.generation, launcherCache.invalidated = oldAt, oldList, oldGen, oldInv
		launcherCache.Unlock()
		lastPathScan.Lock()
		lastPathScan.got = oldScan
		lastPathScan.Unlock()
	})

	dir := t.TempDir()
	n := &Node{home: dir}

	// Changing extra_path invalidates launcherCache and lastPathScan.
	n.setExtraPath([]string{filepath.Join(dir, "bin")})
	launcherCache.Lock()
	if !launcherCache.at.IsZero() || launcherCache.generation <= baseGen || launcherCache.invalidated <= baseInv {
		t.Fatalf("setExtraPath did not invalidate launcherCache: at=%v gen=%d->%d inv=%d->%d",
			launcherCache.at, baseGen, launcherCache.generation, baseInv, launcherCache.invalidated)
	}
	launcherCache.at = time.Now()
	afterGen, afterInv := launcherCache.generation, launcherCache.invalidated
	launcherCache.Unlock()
	lastPathScan.Lock()
	if lastPathScan.got != nil {
		lastPathScan.Unlock()
		t.Fatal("setExtraPath did not clear lastPathScan.got")
	}
	lastPathScan.Unlock()

	// Applying the same extra_path again is a no-op and preserves the warm cache.
	n.setExtraPath([]string{filepath.Join(dir, "bin")})
	launcherCache.Lock()
	defer launcherCache.Unlock()
	if launcherCache.at.IsZero() || launcherCache.generation != afterGen || launcherCache.invalidated != afterInv {
		t.Fatalf("unchanged setExtraPath invalidated warm launcherCache: at=%v gen=%d->%d inv=%d->%d",
			launcherCache.at, afterGen, launcherCache.generation, afterInv, launcherCache.invalidated)
	}
}
