// Unified icon renderer for nav_page entries across the sidebar, icon rail, drawer grid, More menu,
// extension page header, Settings › Sidebar pages, and ⌘K (#17983).
// Reuses the shared cached ExtensionLogo when `iconUrl` is present, falling back seamlessly to the
// semantic <Icon /> glyph while loading, when `iconUrl` is omitted, or if artwork fails to load.
// ExtensionLogo is loaded on first brand-icon render so cold first screens without extension brand
// icons add no logo-fetching code to FIRST_SCREEN_BUDGET.
import { useEffect, useState, type CSSProperties, type ComponentType, type ReactNode } from "react";
import { cx } from "../../ui/atoms";
import { Icon, type IconName } from "../../ui/icons";

type LogoProps = {
  url?: string;
  manager?: string;
  className: string;
  iconSize: number;
  fallback?: ReactNode;
  onShown?: (shown: boolean) => void;
};

let cachedLogo: ComponentType<LogoProps> | null = null;
let loadingLogo: Promise<ComponentType<LogoProps>> | null = null;

function loadExtensionLogo(): Promise<ComponentType<LogoProps>> {
  return (loadingLogo ??= import("../../ui/ExtensionLogo").then(
    (m) => (cachedLogo = m.ExtensionLogo),
    (err) => {
      loadingLogo = null;
      throw err;
    },
  ));
}

function DeferredExtensionLogo(props: LogoProps) {
  const [Logo, setLogo] = useState<ComponentType<LogoProps> | null>(() => cachedLogo);
  useEffect(() => {
    if (Logo) return;
    let live = true;
    void loadExtensionLogo().then(
      (Loaded) => {
        if (live) setLogo(() => Loaded);
      },
      () => {}, // Chunk load failed: keep the semantic fallback glyph without breaking navigation.
    );
    return () => {
      live = false;
    };
  }, [Logo]);
  if (!Logo) {
    return (
      <span aria-hidden="true" className={`relative ${props.className}`}>
        {props.fallback}
      </span>
    );
  }
  return <Logo {...props} />;
}

export interface NavPageIconProps {
  icon: IconName;
  iconUrl?: string;
  /** Default manager ("") for default-manager nav_page entries; pass an explicit manager if scoped elsewhere. */
  manager?: string;
  size: number;
  stroke?: number;
  /** Box classes for the brand logo tile (e.g. "size-4 rounded-[4px]" or "size-7 rounded-[8px] bg-field text-ink-2"). */
  logoClassName: string;
  /** Optional outer box rendered when `iconUrl` is absent (for framed tiles such as ExtensionNavPage header and SidebarPages). */
  frameClassName?: string;
  /** Class applied to the semantic fallback glyph when rendered directly or as ExtensionLogo's fallback. */
  fallbackClassName?: string;
  /** Inline style applied only to the semantic fallback glyph (e.g. pageIconStyle for Colored sidebar icons). */
  fallbackStyle?: CSSProperties;
}

export function NavPageIcon({
  icon,
  iconUrl,
  manager = "",
  size,
  stroke,
  logoClassName,
  frameClassName,
  fallbackClassName,
  fallbackStyle,
}: NavPageIconProps) {
  const glyph = <Icon name={icon} size={size} stroke={stroke} className={fallbackClassName} />;
  const url = iconUrl?.trim();
  if (!url) {
    if (frameClassName) {
      return (
        <span className={frameClassName} style={fallbackStyle}>
          {glyph}
        </span>
      );
    }
    return fallbackStyle ? (
      <span className="inline-flex items-center justify-center" style={fallbackStyle}>
        {glyph}
      </span>
    ) : (
      glyph
    );
  }
  const fallback = fallbackStyle ? (
    <span className="inline-flex items-center justify-center" style={fallbackStyle}>
      {glyph}
    </span>
  ) : (
    glyph
  );
  return (
    <DeferredExtensionLogo
      url={url}
      manager={manager}
      iconSize={size}
      className={cx("flex shrink-0 items-center justify-center overflow-hidden", logoClassName)}
      fallback={fallback}
    />
  );
}
