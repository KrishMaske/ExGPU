"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useEvents } from "@/lib/events-context";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { Logo } from "./Logo";
import { TabBar, type Tab } from "./TabBar";
import { cx } from "./ui";
import { ThemeToggle } from "@/lib/theme";
import { CommandPalette, openCommandPalette } from "./CommandPalette";

const TABS: Array<Tab & { short?: string }> = [
  { href: "/app", label: "Overview", short: "Home" },
  { href: "/app/rent", label: "Rent" },
  { href: "/app/rentals", label: "My Rentals", short: "Rentals" },
  { href: "/app/provide", label: "Provide" },
  { href: "/app/billing", label: "Billing" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const { connected, events } = useEvents();
  const { user, signOut } = useAuth();
  const [balance, setBalance] = useState<number | null>(null);
  const headerRef = useRef<HTMLElement>(null);
  const latestEventId = events[0]?.id;

  // Publish the header's real height so the pages' sticky filter bars can dock flush beneath
  // it. A hard-coded offset drifts the moment the nav wraps to two lines on a narrow screen,
  // leaving either a gap that content scrolls through or an overlap that hides a row of cards.
  useEffect(() => {
    const el = headerRef.current;
    if (!el) return;
    const publish = () =>
      document.documentElement.style.setProperty("--app-header-h", `${el.offsetHeight}px`);
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // The balance lives in the header because it gates every action in the product — you
  // cannot meaningfully rent without it — so it should never require navigating to find.
  useEffect(() => {
    let alive = true;
    api
      .myBalance()
      .then((b) => alive && setBalance(b.balance))
      .catch(() => alive && setBalance(null));
    return () => {
      alive = false;
    };
    // Refetch whenever anything happens to this user: billing, top-ups, kills.
  }, [latestEventId]);

  return (
    <div className="min-h-screen bg-surface-muted">
      <header
        ref={headerRef}
        className="sticky top-0 z-40 border-b border-line bg-surface/85 backdrop-blur-md"
      >
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-16 items-center justify-between gap-4">
            <Link href="/app" className="shrink-0">
              <Logo />
            </Link>

            <div className="flex items-center gap-3">
              <SearchTrigger />
              <BalanceChip balance={balance} />
              <ConnectionDot connected={connected} />
              <ThemeToggle className="hidden h-11 w-11 items-center justify-center rounded-full text-ink-muted transition hover:bg-surface-sunken hover:text-ink sm:flex" />
              <AccountMenu email={user?.email ?? null} onSignOut={() => void signOut()} />
            </div>
          </div>

          <div className="hidden sm:block">
            <TabBar tabs={TABS} />
          </div>
        </div>
      </header>

      <main id="main" className="mx-auto max-w-7xl px-4 py-8 pb-28 sm:px-6 sm:pb-8 lg:px-8">
        <div className="animate-fade-up">{children}</div>
      </main>

      <MobileNav />
      <CommandPalette />
    </div>
  );
}

/**
 * Opens the command palette. Shows the real shortcut so it is discoverable — a palette nobody
 * knows about is the same as no palette.
 */
function SearchTrigger() {
  return (
    <button
      type="button"
      onClick={openCommandPalette}
      aria-label="Search pages and actions"
      className="hidden items-center gap-2 rounded-full border border-line bg-surface-sunken py-1.5 pl-3 pr-2 text-sm text-ink-muted transition hover:border-ink-faint hover:text-ink md:inline-flex"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
        <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="1.8" />
        <path d="m16.5 16.5 4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      </svg>
      Search
      <kbd className="rounded border border-line bg-surface px-1.5 py-0.5 font-mono text-[10px]">
        ⌘K
      </kbd>
    </button>
  );
}

/**
 * Bottom navigation for phones.
 *
 * <p>The top TabBar scrolls horizontally on a narrow screen, which hides whichever tabs do not
 * fit and puts every destination out of thumb reach. A fixed bottom bar is the platform
 * convention for exactly this, and it means the five destinations are always one tap away.
 * The top bar is hidden below `sm` so the two never both appear.
 */
function MobileNav() {
  const pathname = usePathname();
  const isActive = (href: string) =>
    href === "/app" ? pathname === "/app" : pathname.startsWith(href);

  return (
    <nav
      aria-label="Primary"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-md sm:hidden"
    >
      <div className="mx-auto grid max-w-lg grid-cols-5">
        {TABS.map((tab) => {
          const active = isActive(tab.href);
          return (
            <Link
              key={tab.href}
              href={tab.href}
              aria-current={active ? "page" : undefined}
              className={cx(
                "flex min-h-[3.75rem] flex-col items-center justify-center gap-1 px-1 text-[11px] font-medium transition-colors",
                active ? "text-brand-600" : "text-ink-muted"
              )}
            >
              <MobileIcon name={tab.href} active={active} />
              <span className="truncate">{tab.short ?? tab.label}</span>
            </Link>
          );
        })}
      </div>
    </nav>
  );
}

function MobileIcon({ name, active }: { name: string; active: boolean }) {
  const stroke = active ? 2 : 1.7;
  const common = {
    width: 20,
    height: 20,
    viewBox: "0 0 24 24",
    fill: "none",
    "aria-hidden": true as const,
  };
  switch (name) {
    case "/app":
      return (
        <svg {...common}>
          <path d="M3.5 10.5 12 4l8.5 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-4v-6h-6v6H5A1.5 1.5 0 0 1 3.5 19Z" stroke="currentColor" strokeWidth={stroke} strokeLinejoin="round" />
        </svg>
      );
    case "/app/rent":
      return (
        <svg {...common}>
          <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth={stroke} />
          <path d="m16 16 4.5 4.5" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" />
        </svg>
      );
    case "/app/rentals":
      return (
        <svg {...common}>
          <rect x="3.5" y="5.5" width="17" height="14" rx="2.5" stroke="currentColor" strokeWidth={stroke} />
          <path d="M3.5 10h17M8 3.5v3M16 3.5v3" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" />
        </svg>
      );
    case "/app/provide":
      return (
        <svg {...common}>
          <rect x="3.5" y="4.5" width="17" height="7" rx="2" stroke="currentColor" strokeWidth={stroke} />
          <rect x="3.5" y="13.5" width="17" height="6" rx="2" stroke="currentColor" strokeWidth={stroke} />
          <path d="M7 8h.01M7 16.5h.01" stroke="currentColor" strokeWidth={stroke + 0.4} strokeLinecap="round" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <rect x="3" y="6" width="18" height="12" rx="2.5" stroke="currentColor" strokeWidth={stroke} />
          <path d="M3 10.5h18" stroke="currentColor" strokeWidth={stroke} />
        </svg>
      );
  }
}

/** Balance as a header pill; turns amber at zero because that state blocks compute. */
function BalanceChip({ balance }: { balance: number | null }) {
  const empty = balance !== null && balance <= 0;
  return (
    <Link
      href="/app/billing"
      className={cx(
        "hidden items-center gap-2 rounded-full border px-3.5 py-1.5 text-sm font-medium transition sm:inline-flex",
        empty
          ? "border-caution/30 bg-caution/[0.08] text-caution hover:bg-caution/[0.12]"
          : "border-line bg-surface-sunken text-ink hover:border-ink-faint"
      )}
      title={empty ? "Your balance is empty — compute will not run" : "Token balance"}
    >
      <span className="tnum">{balance === null ? "—" : money(balance)}</span>
      {empty && <span className="text-xs">Add funds</span>}
    </Link>
  );
}

/** Live-connection indicator. Title carries the meaning; the dot is a glanceable summary. */
function ConnectionDot({ connected }: { connected: boolean }) {
  return (
    <span
      title={connected ? "Live updates connected" : "Live updates offline"}
      className="hidden items-center gap-1.5 text-xs text-ink-muted sm:inline-flex"
    >
      <span
        className={cx(
          "h-1.5 w-1.5 rounded-full",
          connected ? "bg-positive" : "bg-ink-faint"
        )}
      />
      {connected ? "Live" : "Offline"}
    </span>
  );
}

function AccountMenu({
  email,
  onSignOut,
}: {
  email: string | null;
  onSignOut: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const firstItemRef = useRef<HTMLAnchorElement>(null);

  // Close on outside click and on Escape — a dropdown that traps the user is worse than none.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  useEffect(() => {
    if (open) firstItemRef.current?.focus();
  }, [open]);

  const initial = (email ?? "?").charAt(0).toUpperCase();

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        onClick={() => setOpen((o) => !o)}
        aria-label="Open account menu"
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex h-11 w-11 items-center justify-center rounded-full bg-brand-600 text-sm font-semibold text-white transition hover:bg-brand-700"
      >
        {initial}
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-12 w-[min(18rem,calc(100vw-2rem))] animate-slide-in overflow-hidden rounded-xl border border-line bg-surface shadow-lift"
        >
          <div className="border-b border-line px-4 py-3">
            <p className="text-xs text-ink-muted">Signed in as</p>
            <p className="truncate text-sm font-medium text-ink" title={email ?? ""}>
              {email ?? "—"}
            </p>
          </div>
          <Link
            ref={firstItemRef}
            href="/"
            role="menuitem"
            className="block px-4 py-2.5 text-sm text-ink-soft transition hover:bg-surface-sunken"
            onClick={() => setOpen(false)}
          >
            Home
          </Link>
          <Link
            href="/diagnostics"
            role="menuitem"
            className="block px-4 py-2.5 text-sm text-ink-soft transition hover:bg-surface-sunken"
            onClick={() => setOpen(false)}
          >
            Diagnostics
          </Link>
          <button
            onClick={onSignOut}
            role="menuitem"
            className="block w-full px-4 py-2.5 text-left text-sm text-negative transition hover:bg-negative/[0.06]"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
