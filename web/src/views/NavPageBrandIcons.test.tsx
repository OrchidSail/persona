// @vitest-environment jsdom
// Official extension brand icons across all nav_page surfaces (#17983):
// desktop expanded Sidebar (PageGroup + MorePages), collapsed SidebarRail + More menu,
// mobile NavGrid + More menu, ExtensionNavPage top bar, Settings › Sidebar pages, and ⌘K Palette.
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UIPlace } from "../lib/extension-ui";

vi.hoisted(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  window.matchMedia ??= ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia;
});

const BRAND_ICON = "/api/v1/extensions/example-brand/-/icon?v=2.0.0";
const FAIL_ICON = "/api/v1/extensions/example-fail/-/icon?v=1.0.0";
const HIDDEN_ICON = "/api/v1/extensions/example-hidden/-/icon?v=3.1.0";

const state = vi.hoisted(() => ({ places: [] as UIPlace[] }));

vi.mock("../lib/extension-slots", async (original) => ({
  ...(await original<typeof import("../lib/extension-slots")>()),
  useExtensionSlot: (placement: string) => state.places.filter((p) => p.placement === placement),
  useExtensionPlace: (extension: string, id: string) => state.places.find((p) => p.extension === extension && p.id === id),
}));

vi.mock("./settings/ExtensionView", () => ({
  ExtensionUI: () => null,
}));

import { initI18n } from "../i18n";
import { setToken } from "../lib/api";
import { setSidebarCollapsed } from "../lib/layout";
import { resetManagerChoice } from "../lib/manager-choice";
import { setSidebarIconMode } from "../lib/sidebar-icons";
import { resetSidebarPrefs, setPageShown } from "../lib/sidebar-pages";
import { reset } from "../lib/store";
import { ExtensionNavPage } from "./extensions/ExtensionSlots";
import { Palette } from "./Palette";
import { SidebarPagesGroup } from "./settings/SidebarPages";
import { NavGrid, Sidebar, SidebarRail, useNavPages, type NavPage } from "./Sidebar";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;
let iconFetches: string[];

const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
};

const waitForEl = async <T extends Element>(query: () => T | null): Promise<T> => {
  let found: T | null = null;
  await vi.waitFor(async () => {
    await act(async () => {
      await Promise.resolve();
    });
    found = query();
    expect(found).not.toBeNull();
  });
  return found!;
};

function defaultPlaces(): UIPlace[] {
  return [
    {
      extension: "example-brand",
      extension_name: "Example Brand",
      id: "home",
      title: "Brand Home",
      placement: "nav_page",
      icon: "calendar",
      icon_color: "blue",
      icon_url: BRAND_ICON,
      badge: { text: "3", tone: "neutral" },
    },
    {
      extension: "example-plain",
      extension_name: "Example Plain",
      id: "notes",
      title: "Plain Notes",
      placement: "nav_page",
      icon: "list",
      icon_color: "green",
    },
    {
      extension: "-pages",
      extension_name: "Persona",
      id: "index",
      title: "Dashboards",
      placement: "nav_page",
      icon: "chart",
    },
  ];
}

const linkByLabel = (label: string) =>
  [...host.querySelectorAll<HTMLAnchorElement>("a")].find(
    (a) => (a.getAttribute("aria-label") ?? "").startsWith(label) || a.textContent?.includes(label) || a.title.startsWith(label),
  )!;

beforeEach(async () => {
  await initI18n("en");
  localStorage.clear();
  resetSidebarPrefs();
  resetManagerChoice();
  setSidebarCollapsed(false);
  setSidebarIconMode("monochrome");
  setToken(`synthetic-${Math.random()}`);
  state.places = defaultPlaces();
  iconFetches = [];
  let blobSeq = 0;
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => `blob:brand-icon-${++blobSeq}`) });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      if (input.includes("/-/icon")) {
        iconFetches.push(input);
        if (input.includes("example-fail")) {
          return new Response("not found", { status: 404, headers: { "Content-Type": "text/plain" } });
        }
        return new Response("png-bytes", { status: 200, headers: { "Content-Type": "image/png" } });
      }
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    }),
  );
  history.replaceState(null, "", "/");
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  reset();
  vi.unstubAllGlobals();
});

describe("nav_page official brand icons (#17983)", () => {
  it("exposes iconUrl from UIPlace.icon_url in useNavPages and omits it for plain extensions, built-ins, and -pages", async () => {
    let captured: NavPage[] = [];
    function Capture() {
      captured = useNavPages().all;
      return null;
    }
    act(() => root.render(<Capture />));
    await flush();
    expect(captured.find((p) => p.k === "p:example-brand/home")?.iconUrl).toBe(BRAND_ICON);
    expect(captured.find((p) => p.k === "p:example-plain/notes")?.iconUrl).toBeUndefined();
    expect(captured.find((p) => p.k === "p:-pages/index")?.iconUrl).toBeUndefined();
    expect(captured.find((p) => p.k === "inbox")?.iconUrl).toBeUndefined();
  });

  it("renders ExtensionLogo in desktop Sidebar rows and keeps semantic fallback + icon_color when icon_url is absent or fails", async () => {
    setSidebarIconMode("colored");
    state.places = [
      ...defaultPlaces(),
      {
        extension: "example-fail",
        extension_name: "Example Fail",
        id: "broken",
        title: "Broken Icon",
        placement: "nav_page",
        icon: "bell",
        icon_color: "orange",
        icon_url: FAIL_ICON,
      },
    ];
    for (const k of ["p:example-brand/home", "p:example-plain/notes", "p:-pages/index", "p:example-fail/broken"]) {
      setPageShown(k, true);
    }
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();

    // Extension with icon_url: renders ExtensionLogo at 16px (size-4 rounded-[4px]), no tint style on outer wrapper
    const brandRow = linkByLabel("Brand Home");
    const brandImg = await waitForEl(() => brandRow.querySelector<HTMLImageElement>("[data-extension-logo] img"));
    const brandTile = brandRow.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(brandTile).not.toBeNull();
    expect(brandTile.className).toContain("size-4");
    expect(brandTile.className).toContain("rounded-[4px]");
    expect(brandTile.className).toContain("overflow-hidden");
    expect(brandTile.parentElement!.hasAttribute("style")).toBe(false);
    expect(brandImg.src).toContain("blob:brand-icon-");
    expect(brandImg.hasAttribute("style")).toBe(false);
    act(() => brandImg.dispatchEvent(new Event("load")));
    expect(brandTile.querySelector("svg")).toBeNull();

    // Plain extension without icon_url: keeps semantic icon and Colored icon_color style
    const plainRow = linkByLabel("Plain Notes");
    expect(plainRow.querySelector("[data-extension-logo]")).toBeNull();
    const plainSvg = plainRow.querySelector("svg")!;
    expect(plainSvg.parentElement!.style.color).toBe("var(--hue-green)");

    // Synthetic -pages: keeps semantic icon without ExtensionLogo
    const dashRow = linkByLabel("Dashboards");
    expect(dashRow.querySelector("[data-extension-logo]")).toBeNull();
    expect(dashRow.querySelector("svg")).not.toBeNull();

    // Extension whose icon fetch fails (404): seamlessly falls back to semantic glyph with Colored icon_color preserved
    const failRow = linkByLabel("Broken Icon");
    const failTile = await waitForEl(() => failRow.querySelector<HTMLElement>("[data-extension-logo]"));
    await flush();
    expect(failTile.querySelector("img")).toBeNull();
    const failSvg = failTile.querySelector("svg")!;
    expect(failSvg).not.toBeNull();
    expect(failSvg.parentElement!.style.color).toBe("var(--hue-orange)");
  });

  it("renders brand icons inside desktop Sidebar expanded MorePages when hidden extension page is active", async () => {
    state.places = [
      ...defaultPlaces(),
      {
        extension: "example-hidden",
        extension_name: "Example Hidden",
        id: "more",
        title: "Hidden Brand",
        placement: "nav_page",
        icon: "star",
        icon_url: HIDDEN_ICON,
      },
    ];
    setPageShown("p:example-hidden/more", false);
    act(() =>
      root.render(
        <Sidebar route={{ name: "extension", extension: "example-hidden", ui: "more" }} onSearch={() => {}} onNewAgent={() => {}} />,
      ),
    );
    const hiddenImg = await waitForEl(() => linkByLabel("Hidden Brand")?.querySelector<HTMLImageElement>("[data-extension-logo] img") ?? null);
    expect(hiddenImg.src).toContain("blob:brand-icon-");
  });

  it("falls back to semantic icon when a loaded brand image fires an img error event", async () => {
    setSidebarIconMode("colored");
    act(() => root.render(<Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();
    const brandTile = linkByLabel("Brand Home").querySelector<HTMLElement>("[data-extension-logo]")!;
    const img = brandTile.querySelector<HTMLImageElement>("img")!;
    act(() => img.dispatchEvent(new Event("error")));
    expect(brandTile.querySelector("img")).toBeNull();
    const fallbackSvg = brandTile.querySelector("svg")!;
    expect(fallbackSvg).not.toBeNull();
    expect(fallbackSvg.parentElement!.style.color).toBe("var(--hue-blue)");
  });

  it("renders brand icons in collapsed SidebarRail, mobile NavGrid, and their More overflow menu", async () => {
    setSidebarIconMode("colored");
    state.places = [
      ...defaultPlaces(),
      {
        extension: "example-hidden",
        extension_name: "Example Hidden",
        id: "more",
        title: "Hidden Brand",
        placement: "nav_page",
        icon: "star",
        icon_color: "purple",
        icon_url: HIDDEN_ICON,
      },
    ];
    setPageShown("p:example-hidden/more", false);

    // 1. Collapsed SidebarRail + More menu
    act(() => root.render(<SidebarRail route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />));
    await flush();
    const railLink = linkByLabel("Brand Home");
    expect(railLink.hasAttribute("style")).toBe(false);
    const railTile = railLink.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(railTile).not.toBeNull();
    expect(railTile.className).toContain("size-[18px]");
    expect(railTile.className).toContain("rounded-[4px]");
    expect(railTile.querySelector("img")).not.toBeNull();
    // Badge dot remains present on the rail link
    expect(railLink.querySelector("span.rounded-full")).not.toBeNull();

    // Plain extension still keeps its Colored style on the rail link
    expect(linkByLabel("Plain Notes").style.color).toBe("var(--hue-green)");

    // Open More overflow menu from the rail
    const railMoreBtn = host.querySelector<HTMLButtonElement>('button[aria-haspopup="menu"]')!;
    act(() => railMoreBtn.click());
    await flush();
    const menu = document.body.querySelector<HTMLElement>('[role="menu"]')!;
    expect(menu).not.toBeNull();
    const hiddenItem = [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')].find((el) => el.textContent?.includes("Hidden Brand"))!;
    const menuTile = hiddenItem.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(menuTile).not.toBeNull();
    expect(menuTile.className).toContain("size-4");
    expect(menuTile.className).toContain("rounded-[4px]");
    expect(menuTile.querySelector("img")).not.toBeNull();

    // 2. Mobile NavGrid
    function DrawerPages() {
      const { pages, more } = useNavPages();
      return <NavGrid pages={pages} more={more} activeKey="home" go={() => () => {}} />;
    }
    act(() => root.render(<DrawerPages />));
    await flush();
    const gridLink = linkByLabel("Brand Home");
    const gridTile = gridLink.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(gridTile).not.toBeNull();
    expect(gridTile.className).toContain("size-[18px]");
    expect(gridTile.className).toContain("rounded-[4px]");
    expect(gridTile.querySelector("img")).not.toBeNull();
    // Badge count overlay ("3") remains rendered cleanly
    expect(gridLink.textContent).toContain("3");
  });

  it("renders ExtensionLogo in ExtensionNavPage header when icon_url is present and falls back otherwise", async () => {
    await act(async () => {
      root.render(<ExtensionNavPage extension="example-brand" ui="home" onToggleSidebar={() => {}} />);
    });
    const img = await waitForEl(() => host.querySelector<HTMLImageElement>("header [data-extension-logo] img"));
    const header = host.querySelector("header")!;
    const tile = header.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(tile).not.toBeNull();
    expect(tile.className).toContain("size-7");
    expect(tile.className).toContain("rounded-[8px]");
    act(() => img.dispatchEvent(new Event("load")));
    expect(tile.querySelector("svg")).toBeNull();

    // Extension without icon_url renders semantic fallback inside size-7 rounded-[8px] box
    await act(async () => {
      root.render(<ExtensionNavPage extension="example-plain" ui="notes" onToggleSidebar={() => {}} />);
    });
    await flush();
    expect(host.querySelector("header [data-extension-logo]")).toBeNull();
    expect(host.querySelector("header span.size-7 svg")).not.toBeNull();
  });

  it("renders brand icons consistently in Settings › Sidebar pages and ⌘K Palette", async () => {
    // Settings › General › Sidebar pages
    act(() => root.render(<SidebarPagesGroup />));
    await flush();
    const brandItem = host.querySelector<HTMLElement>('li[data-page="p:example-brand/home"]')!;
    const settingsTile = brandItem.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(settingsTile).not.toBeNull();
    expect(settingsTile.className).toContain("size-7");
    expect(settingsTile.querySelector("img")).not.toBeNull();
    expect(host.querySelector('li[data-page="p:example-plain/notes"] [data-extension-logo]')).toBeNull();

    // ⌘K Palette "Go to"
    act(() => root.render(<Palette open onClose={() => {}} onNewAgent={() => {}} query="Brand" onQueryChange={() => {}} />));
    await flush();
    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')!;
    const option = [...dialog.querySelectorAll<HTMLElement>('[role="option"]')].find((el) => el.textContent?.includes("Brand Home"))!;
    const paletteTile = option.querySelector<HTMLElement>("[data-extension-logo]")!;
    expect(paletteTile).not.toBeNull();
    expect(paletteTile.className).toContain("size-4");
    expect(paletteTile.className).toContain("rounded-[4px]");
    expect(paletteTile.querySelector("img")).not.toBeNull();
  });

  it("reuses the shared ExtensionLogo cache across Sidebar, SidebarRail, and ExtensionNavPage with a single network fetch", async () => {
    await act(async () => {
      root.render(
        <>
          <Sidebar route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />
          <SidebarRail route={{ name: "home" }} onSearch={() => {}} onNewAgent={() => {}} />
          <ExtensionNavPage extension="example-brand" ui="home" onToggleSidebar={() => {}} />
        </>,
      );
    });
    await vi.waitFor(() => {
      expect(host.querySelectorAll("[data-extension-logo] img").length).toBe(3);
    });
    expect(iconFetches.filter((u) => u === BRAND_ICON)).toHaveLength(1);
  });
});
