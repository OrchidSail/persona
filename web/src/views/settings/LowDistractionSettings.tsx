// Settings › Notifications › Low-distraction mode (#8157): the hub's push grading switch (GET / PUT /push/ln-mode,
// docs/server/server.md "Push grading"). Unlike the rows above it, it is the hub's, so it holds for every device of the
// owner; persona_settings `notifications.low_distraction` changes the same switch after the owner approves a card. A hub
// without the route (404 / 405) has no switch: the group stays away and the page looks as it did.
import { useCallback, useEffect, useRef, useState } from "react";
import { t } from "../../i18n";
import { api, errorText, isAbort, onCredentialChange } from "../../lib/api";
import { lnModeEnabled } from "../../lib/push-intercepts";
import { toast } from "../../ui/atoms";
import { Group, Row, RowFailed, Switch } from "./SettingsParts";

type State = "loading" | "unsupported" | { on: boolean } | { error: string };

function missingRoute(e: unknown): boolean {
  return typeof e === "object" && e !== null && "status" in e && (e.status === 404 || e.status === 405);
}

export function LowDistractionSettings() {
  const [state, setState] = useState<State>("loading");
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setState((s) => (typeof s === "object" && "on" in s ? s : "loading"));
    api<unknown>("/push/ln-mode", { signal: controller.signal }).then(
      (r) => { if (!controller.signal.aborted) setState({ on: lnModeEnabled(r) }); },
      (e) => {
        if (controller.signal.aborted || isAbort(e)) return;
        setState(missingRoute(e) ? "unsupported" : { error: errorText(e) });
      },
    );
  }, []);

  useEffect(() => {
    load();
    const stop = onCredentialChange(load);
    return () => {
      stop();
      abort.current?.abort();
    };
  }, [load]);

  const toggle = async (on: boolean) => {
    abort.current?.abort(); // a read still on its way must not undo this
    setBusy(true);
    setState({ on });
    try {
      const r = await api<unknown>("/push/ln-mode", { method: "PUT", json: { enabled: on } });
      setState({ on: lnModeEnabled(r) });
    } catch (e) {
      setState({ on: !on });
      if (!isAbort(e)) toast(t("settings-notifications.save_failed", { error: errorText(e) }), "error");
    } finally {
      setBusy(false);
    }
  };

  if (state === "unsupported" || state === "loading") return null;
  return (
    <Group anchor="low-distraction" footer={t("settings-notifications.low_distraction_footer")}>
      {"error" in state ? (
        <RowFailed text={t("settings-notifications.low_distraction_load_failed")} retry={t("settings-notifications.retry")} onRetry={load} />
      ) : (
        <Row icon="bellOff" anchor="notify-low-distraction" label={t("settings-notifications.low_distraction")} hint={t("settings-notifications.low_distraction_hint")}>
          <Switch on={state.on} onChange={(on) => void toggle(on)} disabled={busy} label={t("settings-notifications.low_distraction")} />
        </Row>
      )}
    </Group>
  );
}
