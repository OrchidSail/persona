// @vitest-environment jsdom
// Settings › Notifications › Low-distraction mode (#8157): the row shows the hub's switch as GET /push/ln-mode answers,
// a tap PUTs it and shows what the hub answered, a failed write goes back and says so, and a hub without the route
// shows nothing (the page stays as it was).
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { t } from "../../i18n";

type Call = { path: string; method: string; json?: unknown; resolve: (v: unknown) => void; reject: (e: unknown) => void };
const calls: Call[] = [];
vi.mock("../../lib/api", async (orig) => ({
  ...(await orig<typeof import("../../lib/api")>()),
  api: (path: string, init?: { method?: string; json?: unknown }) =>
    new Promise((resolve, reject) => calls.push({ path, method: init?.method ?? "GET", json: init?.json, resolve, reject })),
}));
const toast = vi.fn();
vi.mock("../../ui/atoms", async (orig) => ({ ...(await orig<typeof import("../../ui/atoms")>()), toast: (...a: unknown[]) => toast(...a) }));

const { LowDistractionSettings } = await import("./LowDistractionSettings");

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  calls.length = 0;
  toast.mockReset();
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

const toggle = () => host.querySelector<HTMLButtonElement>('[role="switch"]');
const last = () => calls[calls.length - 1];

async function mount(answer: unknown) {
  act(() => root.render(<LowDistractionSettings />));
  expect(calls[0]).toMatchObject({ path: "/push/ln-mode", method: "GET" });
  await act(async () => calls[0].resolve(answer));
}

describe("LowDistractionSettings", () => {
  it("shows the hub's switch with what it does, and turns it on and back off", async () => {
    await mount({ enabled: false });
    expect(toggle()?.getAttribute("aria-checked")).toBe("false");
    expect(toggle()?.getAttribute("aria-label")).toBe(t("settings-notifications.low_distraction"));
    expect(host.textContent).toContain(t("settings-notifications.low_distraction_footer"));

    act(() => toggle()!.click());
    expect(last()).toMatchObject({ path: "/push/ln-mode", method: "PUT", json: { enabled: true } });
    expect(toggle()?.disabled).toBe(true); // one write at a time
    await act(async () => last().resolve({ enabled: true }));
    expect(toggle()?.getAttribute("aria-checked")).toBe("true");
    expect(toggle()?.disabled).toBe(false);

    act(() => toggle()!.click());
    expect(last()).toMatchObject({ method: "PUT", json: { enabled: false } });
    await act(async () => last().resolve({ enabled: false }));
    expect(toggle()?.getAttribute("aria-checked")).toBe("false");
  });

  it("goes back and says so when the hub refuses the change", async () => {
    await mount({ enabled: true });
    act(() => toggle()!.click());
    await act(async () => last().reject(Object.assign(new Error("hub away"), { status: 503 })));
    expect(toggle()?.getAttribute("aria-checked")).toBe("true");
    expect(toast).toHaveBeenCalledWith(expect.stringContaining("hub away"), "error");
  });

  it("shows nothing for a hub without the switch", async () => {
    await mount(undefined);
    expect(toggle()).not.toBeNull(); // a body without enabled reads off
    act(() => root.render(<></>));
    calls.length = 0;
    act(() => root.render(<LowDistractionSettings />));
    await act(async () => calls[0].reject(Object.assign(new Error("not found"), { status: 404 })));
    expect(host.textContent).toBe("");
  });

  it("offers Retry when the switch cannot be read", async () => {
    act(() => root.render(<LowDistractionSettings />));
    await act(async () => calls[0].reject(Object.assign(new Error("offline"), { status: 0 })));
    expect(toggle()).toBeNull();
    const retry = [...host.querySelectorAll("button")].find((b) => b.textContent === t("settings-notifications.retry"))!;
    act(() => retry.click());
    await act(async () => last().resolve({ enabled: true }));
    expect(toggle()?.getAttribute("aria-checked")).toBe("true");
  });
});
