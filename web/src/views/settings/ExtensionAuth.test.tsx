// @vitest-environment jsdom
// The "Sign in" group of an extension's detail (#4507): its state, "Sign in" / "Sign in again", and "Sign out" only when signed in,
// plus fields steps that also provide a safe sign-in URL.
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.hoisted(() => {
  window.matchMedia ??= ((query: string) => ({
    matches: false, media: query, onchange: null,
    addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent: () => false,
  })) as typeof window.matchMedia;
});
vi.mock("../../lib/api", async (original) => ({ ...(await original<typeof import("../../lib/api")>()), api: vi.fn() }));
vi.mock("../../ui/atoms", async (original) => ({ ...(await original<typeof import("../../ui/atoms")>()), toast: vi.fn() }));

import { t } from "../../i18n";
import { api } from "../../lib/api";
import type { AuthPrompt, ExtensionAuth } from "../../lib/extension-auth";
import { AuthGroup, SignInDialog } from "./ExtensionAuth";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const draw = (auth: ExtensionAuth, enabled = true) => renderToStaticMarkup(<AuthGroup id="cis" auth={auth} enabled={enabled} onChange={() => {}} />);

describe("AuthGroup", () => {
  it("offers signing in when not signed in", () => {
    const html = draw({ title: "飞书登录", description: "用飞书确认身份后才能查", state: "signed_out" });
    expect(html).toContain("登录");
    expect(html).toContain("飞书登录");
    expect(html).toContain("未登录");
    expect(html).toContain("用飞书确认身份后才能查");
    expect(html).not.toContain("退出登录");
  });

  it("offers signing in again and out when signed in", () => {
    const html = draw({ title: "飞书登录", state: "signed_in", account: "me@example.com" });
    expect(html).toContain("已登录：me@example.com");
    expect(html).toContain("重新登录");
    expect(html).toContain("退出登录");
  });

  it("says a switched-off extension has to be on first", () => {
    const html = draw({ title: "飞书登录", state: "unknown" }, false);
    expect(html).toContain("扩展未运行");
    expect(html).toContain("启用扩展后才能登录。");
    expect(html).toContain("disabled");
  });
});

describe("SignInDialog fields with url", () => {
  const transport = vi.mocked(api);
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    transport.mockReset();
    host = document.createElement("div");
    document.body.append(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  const renderPrompt = async (prompt: AuthPrompt) => {
    transport.mockResolvedValueOnce(prompt);
    await act(async () => {
      root.render(<SignInDialog id="example" title="Example sign-in" onClose={() => {}} onDone={() => {}} />);
    });
    return document.querySelector<HTMLElement>("[role=\"dialog\"]")!;
  };

  it("renders open-page and copy-link controls above fields when url is safe https", async () => {
    const dlg = await renderPrompt({
      step: "oauth",
      url: "https://accounts.example.com/oauth?state=123",
      fields: [{ name: "code", label: "Authorization code" }],
    });
    const openLink = [...dlg.querySelectorAll("a")].find((a) => a.textContent?.includes(t("settings-extensionauth.open_page")));
    expect(openLink?.getAttribute("href")).toBe("https://accounts.example.com/oauth?state=123");
    const copyBtn = dlg.querySelector(`button[aria-label="${t("settings-extensionauth.copy_link")}"]`);
    expect(copyBtn).not.toBeNull();
    expect(dlg.querySelector("input")).not.toBeNull();
  });

  it("renders open-page and copy-link controls for loopback http url", async () => {
    const dlg = await renderPrompt({
      step: "oauth",
      url: "http://127.0.0.1:8080/cb",
      fields: [{ name: "code", label: "Authorization code" }],
    });
    const openLink = [...dlg.querySelectorAll("a")].find((a) => a.textContent?.includes(t("settings-extensionauth.open_page")));
    expect(openLink?.getAttribute("href")).toBe("http://127.0.0.1:8080/cb");
    expect(dlg.querySelector(`button[aria-label="${t("settings-extensionauth.copy_link")}"]`)).not.toBeNull();
  });

  it("hides link controls when fields url is unsafe", async () => {
    const dlg = await renderPrompt({
      step: "oauth",
      url: "http://example.com/oauth",
      fields: [{ name: "code", label: "Authorization code" }],
    });
    const openLink = [...dlg.querySelectorAll("a")].find((a) => a.textContent?.includes(t("settings-extensionauth.open_page")));
    expect(openLink).toBeUndefined();
    expect(dlg.querySelector(`button[aria-label="${t("settings-extensionauth.copy_link")}"]`)).toBeNull();
    expect(dlg.querySelector("input")).not.toBeNull();
  });
});
