"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { cx } from "./ui";

export interface Tab {
  href: string;
  label: string;
}

/**
 * Horizontal navigation with a sliding underline.
 *
 * <p>The indicator is positioned by measuring the active tab rather than by using a border on
 * the active link. That keeps the transition continuous when moving between tabs of different
 * widths, which a per-item border cannot do — it would pop rather than slide.
 *
 * <p>Position is recalculated on route change and on resize, and the measurement is skipped
 * on first paint so the indicator does not animate in from zero width.
 */
export function TabBar({ tabs }: { tabs: Tab[] }) {
  const pathname = usePathname();
  const listRef = useRef<HTMLDivElement>(null);
  const [indicator, setIndicator] = useState<{ left: number; width: number } | null>(null);

  const isActive = (href: string) =>
    href === "/app" ? pathname === "/app" : pathname.startsWith(href);

  const activeIndex = tabs.findIndex((t) => isActive(t.href));

  useEffect(() => {
    const measure = () => {
      const list = listRef.current;
      if (!list || activeIndex < 0) {
        setIndicator(null);
        return;
      }
      const el = list.children[activeIndex] as HTMLElement | undefined;
      if (!el) return;
      setIndicator({ left: el.offsetLeft, width: el.offsetWidth });
    };

    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [activeIndex, pathname]);

  return (
    <nav className="relative" aria-label="Dashboard">
      {/* -mb-px pulls the tabs onto the header's bottom border so the indicator sits on it. */}
      <div
        ref={listRef}
        className="-mb-px flex items-center gap-1 overflow-x-auto"
      >
        {tabs.map((tab) => {
          const active = isActive(tab.href);
          return (
            <Link
              key={tab.href}
              href={tab.href}
              aria-current={active ? "page" : undefined}
              className={cx(
                "relative flex min-h-11 items-center whitespace-nowrap rounded-t-lg px-4 py-3 text-sm font-medium transition-colors",
                active
                  ? "text-brand-700"
                  : "text-ink-muted hover:text-ink"
              )}
            >
              {tab.label}
            </Link>
          );
        })}
      </div>

      {indicator && (
        <span
          aria-hidden
          className="absolute bottom-0 h-0.5 rounded-full bg-brand-600 transition-all duration-300 ease-out"
          style={{ left: indicator.left, width: indicator.width }}
        />
      )}
    </nav>
  );
}
