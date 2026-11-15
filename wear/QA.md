# Wear OS verification

This document separates local checks, synthetic physical-device tests and real-Hub observations. The recorded device acceptance used a Samsung Galaxy Watch8 running Android 16 / API 36 with a 480 × 480 round display. Core UI acceptance was repeated on app `0.2.0`; the subsequent complication acceptance and real-Hub regression used `0.2.1` (3). Other devices and operating-system versions require their own checks.

## Automated checks

| Check | Recorded result | What it establishes |
| --- | --- | --- |
| App and test APK builds | Passed for `0.2.1` | Both packages compile; the test driver is separate from the app |
| App unit tests | 127 passed | Parsing, permissions, scoping, drafts, rendering models, freshness and review regressions |
| Android Lint | Zero errors, 35 advisory warnings | Static analysis; warnings include dependency age, unused resources, accessibility/plural resources and intentionally pinned TLS handling |
| Complication serialization | All five production timelines passed on-device | AndroidX wire conversion and Android `Parcel` round trips for three pet types and two extension types |
| Rotation guard | Seven host-side checks passed | Preference restoration after success, failure and leaked charging rotation; unknown preferences are rejected |
| TLS-debug discovery | Eleven host-side checks passed | Identity checks, stale advertisements, renamed services and refusal to select an unrelated device |
| Optional Hub Status provider | Go tests and package checks passed | SDK/UI contracts, three widget families, locales, permissions, malformed responses and declared platform packaging |

Build and rerun the app checks from `wear/` with the toolchain described in [README](README.md#build):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

Run the host helper checks from the repository root:

```powershell
node --test wear/tools/rotation-guard.test.mjs wear/tools/connect-watch.test.mjs
```

The optional provider has its own [build and test instructions](qa/hub-status-extension/README.md#build-and-local-verification). Build/package success does not establish execution on every declared runtime.

The PR #8554 review regressions add 30 host-side tests: nine registration fault/retry cases, eight shared-deadline boundaries, three removal completion cases, and ten chart drawing-plan checks. Registration tests model failed preferences commits that still change memory, restart recovery, cleanup failure and an unreadable saved credential. Chart tests exercise all six accepted kinds plus zero totals, single slices and donut geometry. These use the production registration/collection/completion/drawing logic; they do not establish Android Canvas pixels, Keystore behavior on a physical watch, or a new device acceptance run. A fresh Gradle run used `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest --rerun-tasks`, with no unit failures or skips. The exchange-response-to-durable-credential gap remains outside local recovery, as described in [pairing](README.md#pairing-and-private-debug-provisioning).

## Physical-device checks with synthetic data

The fixture runs independently of real accounts and agents. Its audit records the accepted operations, allowing visible results to be compared with actual requests. Temporary ADB reverse tunnels were used for the recorded fixture runs; these are not direct-LAN or remote-network evidence.

A small [synthetic capture gallery](qa/screenshots/README.md) preserves reviewed images from those earlier runs. It is not a claim that screenshots were rerun at the final PR commit.

| Area | Observed result |
| --- | --- |
| Questions, plans, permissions and computer choices | Actual controls submitted the supported resolution; cancellation issued no write; protected requests had no enabled approval path |
| Conversation and Inbox | Explicit send, confirmed interruption, reply and handled actions reached the fixture; UI state reconciled with its response |
| Recovery | HTTP 503 and dropped connections preserved cached history and the draft; reconnect sent once; stale HTTP 410 was not treated as an approval |
| Non-voice actions | Continue/seen, explicit AI draft generation, cancel/confirm replacement, dismiss-to-computer, Esc and confirmed Enter passed |
| Multiple hubs | Two fixtures exercised independent encrypted credentials, colliding-ID isolation, partial failure and removal of only the selected extra connection |
| Pet and extension pages | Verified pet assets animated; supported text, metric, progress, row, stat-grid and chart projections rendered; unsupported controls were filtered; error recovery passed |
| System complication selection | Samsung's real editor bound independent short/long extension slots; cancel saved no binding; replacing a source removed the corresponding binding |
| System complication taps | Each selected pet and extension region opened the expected page |
| Natural expiry | Pet short text, long text and image changed to Offline; extension text changed to Update after their deadlines and the face's next redraw |

These tests use the production UI, not a replacement test screen. The temporary [WFF test face](qa/watchface/README.md) has initially empty slots and never chooses a provider automatically. App-page screenshots, previews, successful cache seeding and serialized timelines alone do not prove that a complication was selected or rendered by the system.

The complete core flows were repeated on `0.2.0`; the `0.2.1` checks specifically covered complication timelines, selection, taps and expiry. Interrupted runs, locked-screen captures and setup-only instrumentation results were excluded from passing evidence.

## Physical-device checks with a real Hub

The watch used a separate answer-role credential, encrypted through Android Keystore, and connected directly over LAN HTTPS with the paired public-key pin. The recorded real-Hub checks did not use the synthetic fixture or an ADB reverse tunnel.

| Area | Observed result |
| --- | --- |
| Connection and reads | Credential decryption, authenticated refresh and conversation retrieval passed after app upgrades and final cleanup |
| Real-agent exchange | An earlier `0.1.0` run sent one explicitly designated test message through the visible composer and displayed the agent's reply; later acceptance repeated reads rather than a new live action |
| Pet complications | Verified real-Hub assets and state appeared in all three supported data types; taps opened the pet page |
| Extension availability | An empty catalogue was handled correctly; the separately installed optional Hub Status provider then supplied a real catalogue entry and both accessory families |
| Extension display and navigation | Short and long system-selected complications displayed actual aggregate values; each tap opened the real widget detail |
| Aggregate comparison | Widget values matched independent raw API array counts; filtered watch-list counts were reported separately |
| Expiry and refresh | The two real projections expired at their own deadlines; a foreground refresh restored both and reflected changed Hub data |
| Debug connection discovery | The helper resolved the paired watch's current TLS transport and verified its hardware identity without a port argument |

The [Hub Status provider](qa/hub-status-extension/README.md) is optional. Its recorded runtime execution was Linux ARM64; packaging Android ARM64 and Linux AMD64 does not prove execution on those platforms. Provider installation and sandbox configuration are separate from the watch credential's permissions.

Raw API totals and the watch's visible list have different filtering rules. The `real_surfaces` probe reports both without logging response contents. Scope differences and cache timestamps must also be considered; a fresh read and an earlier cached projection are not a simultaneous snapshot.

Some ADB images omitted WFF layers even while the physical display was complete. Complete captures, accessibility information and physical-screen confirmation were assessed together. An incomplete screenshot alone was not treated as a pass or a rendering defect. Real conversation captures and detailed local logs remain private and are excluded from the repository.

## Reproduce and record acceptance

Use the [physical-device runner procedure](README.md#physical-device-test-runner) to install the separate test APK and preserve the user's saved connection. Run from the repository root:

```powershell
node wear/tools/run-watch-check.mjs --device <watch-debug-address>
node wear/tools/run-watch-check.mjs --device <watch-debug-address> --mode real_surfaces
```

The default mode only reads the saved real Hub. `real_surfaces` reads catalogue/projection availability and primes real caches; it explicitly does not claim system selection, visible face rendering or taps. A passing instrumentation result must contain `INSTRUMENTATION_CODE: -1` and the mode's own success fields; process exit status alone is insufficient.

Follow [system complication acceptance](README.md#system-complication-acceptance) for actual picker selection, tap navigation and natural expiry. Use guarded fixture modes only with their synthetic connection; only run a real action against an explicitly designated test task. Record each data type separately, keep the watch unlocked and wait through the reported deadline and next face redraw.

Use `run-watch-check.mjs` and `capture-watch.mjs` rather than bare UI-automation commands. Android UI-automation disconnect can persist a temporary charging orientation. The helpers preserve the explicit pre-test preference, verify restoration after disconnect and refuse an unreadable baseline; they do not choose a presumed default or enable global automatic rotation. Screenshot-only capture avoids UI automation.

After a temporary test, restore the saved connection, face, provider assignments and any changed settings; remove the test APK, temporary face, tunnels and fixture processes. Keep private screenshots, pairing files and detailed device logs in ignored local storage. Publish only scrubbed or synthetic evidence.

## Limits

- Voice, microphone recording and transcription were deferred. The pre-existing system-dictation entry remains unverified; no recording pipeline, phone companion or cloud-speech substitute is claimed.
- Real approvals and real Inbox sending were not exercised by the later real-Hub checks. Their state transitions were tested against the isolated fixture.
- Background notifications, remote-network behavior, the largest accessibility text size and the broader Wear OS device matrix remain unverified or outside the implementation.
- The watch design is intentionally dark. This acceptance does not claim a separate light visual theme or complete visual-accessibility certification.
- User-specific watch-face changes and system-orientation troubleshooting are outside this module's portable acceptance. Test helpers must preserve observed settings rather than infer an original preference.

See [the Apple Watch capability comparison](PARITY.md) for the remaining platform differences.
