// @vitest-environment jsdom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.hoisted(() => {
  globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} } as unknown as typeof ResizeObserver;
  window.matchMedia ??= ((query: string) => ({ matches: false, media: query, onchange: null, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent: () => false })) as typeof window.matchMedia;
});
import { filterAgentList, resetAgentListFilters } from "../lib/agent-list-filters";
import { seedAgentsDisplay } from "../lib/agents-display";
import { SESSIONS_AGENTS_ONLY_FEATURE } from "../lib/runtime-list";
import { connect, reset } from "../lib/store";
import type { Session } from "../lib/types";
import { AgentsList, Sidebar } from "./Sidebar";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

class FakeSocket {
  static OPEN = 1;
  static all: FakeSocket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  constructor() { FakeSocket.all.push(this); }
  send() {}
  close() { this.readyState = 3; }
  open() { this.readyState = 1; this.onopen?.(); }
  push(msg: Record<string, unknown>) { this.onmessage?.({ data: JSON.stringify(msg) }); }
}

const agent = (id: string): Session => ({
  machine_id: "computer",
  session_id: id,
  title: id,
  cwd: "/code/demo",
  status: "working",
  state: "working",
  updated_at: "2026-01-01T00:00:00Z",
  space_id: "project",
  space_name: "Demo project",
});

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } }));

let host: HTMLDivElement;
let root: Root;

const flush = async () => {
  await act(async () => { await vi.dynamicImportSettled(); });
  for (let i = 0; i < 10; i++) await act(async () => { await Promise.resolve(); });
};

const setupFetch = (herdrRunning = true) => {
  const rows = [agent("First page agent")];
  const fetchMock = vi.fn((input: string) => {
    const url = new URL(input, "http://hub.example.com");
    if (url.pathname.endsWith("/sessions") && !url.pathname.includes("/agents/")) {
      const q = url.searchParams;
      if (q.has("counts")) {
        return json({ total: rows.length, state: { working: rows.length }, machine: { computer: rows.length }, group: {}, project: {}, revision: "r1" });
      }
      return json({ sessions: rows, remove: [], has_more: false, revision: "r1", total: rows.length });
    }
    if (url.pathname.endsWith("/pins")) return json({ sessions: [], spaces: [] });
    if (url.pathname.endsWith("/machines")) {
      return json({ machines: [{ id: "computer", name: "Demo computer", online: true, herdr_running: herdrRunning }] });
    }
    if (url.pathname.endsWith("/todos")) return json({ todos: [] });
    return json({});
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

const connectPagedHub = async () => {
  act(() => {
    connect();
    const socket = FakeSocket.all.at(-1)!;
    socket.open();
    socket.push({ type: "hello", features: ["sessions_list", "sessions_lookup", "sessions_todo", SESSIONS_AGENTS_ONLY_FEATURE] });
  });
  await flush();
};

const agentsBlock = () => host.querySelector<HTMLElement>("[data-sidebar-agents]")!;
const agentsFoldButton = () => [...agentsBlock().querySelectorAll<HTMLButtonElement>(":scope > div > button[aria-expanded]")].find((b) => b.textContent?.trim() === "Agents") ?? null;
const pagedList = () => agentsBlock().querySelector("[data-paged-sessions]");
const foldAllButton = () => agentsBlock().querySelector<HTMLButtonElement>("[data-testid=agents-fold-all]");

beforeEach(() => {
  localStorage.clear();
  resetAgentListFilters();
  reset();
  seedAgentsDisplay({ show_botmux: true, configured: true, show_filter: true });
  FakeSocket.all = [];
  vi.stubGlobal("WebSocket", FakeSocket);
  history.replaceState(null, "", "/");
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  reset();
  resetAgentListFilters();
  vi.unstubAllGlobals();
});

describe("BoundedAgentsBlock folding", () => {
  it("folds and unfolds the whole Agents block when foldable, hiding filter bar, list, and herdr notices", async () => {
    setupFetch(false);
    localStorage.setItem("persona.agentGrouping", "machine");
    await connectPagedHub();
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();

    const btn = agentsFoldButton()!;
    expect(btn).not.toBeNull();
    expect(btn.getAttribute("aria-expanded")).toBe("true");
    expect(btn.textContent?.trim()).toBe("Agents");
    expect(pagedList()).not.toBeNull();
    expect(agentsBlock().querySelector('[role="radiogroup"][aria-label="Agent 分组"]')).not.toBeNull();
    expect(agentsBlock().textContent).toContain("Demo computer");
    expect(agentsBlock().querySelector('[aria-label="新建 Agent"]')).not.toBeNull();

    // Fold
    act(() => btn.click());
    await flush();
    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("false");
    expect(localStorage.getItem("fold:agents")).toBe("1");
    expect(pagedList()).toBeNull();
    expect(agentsBlock().querySelector('[role="radiogroup"][aria-label="Agent 分组"]')).toBeNull();
    expect(agentsBlock().querySelector('[aria-label="新建 Agent"]')).not.toBeNull();

    // Unfold
    act(() => agentsFoldButton()!.click());
    await flush();
    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("true");
    expect(localStorage.getItem("fold:agents")).toBe("0");
    expect(pagedList()).not.toBeNull();
    expect(agentsBlock().querySelector('[role="radiogroup"][aria-label="Agent 分组"]')).not.toBeNull();
  });

  it("remembers folded state across remount and skips paged session fetches while folded", async () => {
    localStorage.setItem("fold:agents", "1");
    localStorage.setItem("persona.agentGrouping", "machine");
    const fetchMock = setupFetch(true);
    await connectPagedHub();
    fetchMock.mockClear();
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();

    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("false");
    expect(pagedList()).toBeNull();
    const sessionCallsWhileFolded = fetchMock.mock.calls.filter(([u]) => new URL(u as string, "http://hub.example.com").pathname.endsWith("/sessions"));
    expect(sessionCallsWhileFolded).toHaveLength(0);

    // Clicking the fold-all button while folded opens the block and loads sessions
    const expandAll = foldAllButton()!;
    expect(expandAll.getAttribute("aria-label")).toBe("全部展开");
    act(() => expandAll.click());
    await flush();

    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("true");
    expect(pagedList()).not.toBeNull();
    const sessionCallsAfterOpen = fetchMock.mock.calls.filter(([u]) => new URL(u as string, "http://hub.example.com").pathname.endsWith("/sessions"));
    expect(sessionCallsAfterOpen.length).toBeGreaterThan(0);
  });

  it("temporarily unfolds when dimFilters.query is non-empty and restores folded state when cleared", async () => {
    localStorage.setItem("fold:agents", "1");
    localStorage.setItem("persona.agentGrouping", "machine");
    setupFetch(true);
    await connectPagedHub();
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();

    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("false");
    expect(pagedList()).toBeNull();

    act(() => filterAgentList("First"));
    await flush();
    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("true");
    expect(pagedList()).not.toBeNull();

    expect(localStorage.getItem("fold:agents")).toBe("1");
    act(() => filterAgentList(""));
    await flush();
    // Clearing the query preserves the stored folded state across remount
    act(() => root.unmount());
    root = createRoot(host);
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();
    expect(agentsFoldButton()!.getAttribute("aria-expanded")).toBe("false");
    expect(pagedList()).toBeNull();
  });

  it("renders a non-foldable plain title and always shows the body when foldable is false (AgentsList)", async () => {
    localStorage.setItem("fold:agents", "1");
    localStorage.setItem("persona.agentGrouping", "machine");
    setupFetch(true);
    await connectPagedHub();
    act(() => root.render(<AgentsList route={{ name: "agent-list" }} onNewAgent={() => {}} />));
    await flush();

    expect(agentsFoldButton()).toBeNull();
    expect(agentsBlock().textContent).toContain("Agents");
    expect(pagedList()).not.toBeNull();
  });
});
