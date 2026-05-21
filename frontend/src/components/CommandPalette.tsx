"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { useTheme } from "@/lib/theme";
import { cx } from "./ui";

/**
 * ⌘K navigation.
 *
 * <p>Five destinations behind a tab bar is fine until you are in the app all day, at which
 * point the fastest path between two pages should be typing where you want to go. Everything
 * offered here is something the palette can actually complete on its own — navigation, theme,
 * sign out, the ops dashboards. Deliberately no "book a rental" style entries: those need a
 * listing chosen first, and an action that opens a page and then asks for more input is a
 * worse version of just clicking the page.
 *
 * <p>Matching is subsequence-based, so "arn" finds "Rent GPUs" the way editors behave, rather
 * than requiring a contiguous substring.
 */

const OPEN_EVENT = "exgpu:open-command-palette";

/** Opens the palette from anywhere — used by the header button and the mobile nav. */
export function openCommandPalette(): void {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

interface Command {
  id: string;
  label: string;
  hint?: string;
  group: string;
  run: () => void;
  keywords?: string;
}

/** Characters of `query` appear in `text` in order — the usual fuzzy-finder contract. */
function subsequenceScore(text: string, query: string): number | null {
  if (!query) return 0;
  const haystack = text.toLowerCase();
  const needle = query.toLowerCase();

  let score = 0;
  let cursor = 0;
  let previous = -1;

  for (const char of needle) {
    const found = haystack.indexOf(char, cursor);
    if (found === -1) return null;
    // Adjacent matches and word-start matches rank above scattered ones.
    if (found === previous + 1) score += 3;
    if (found === 0 || haystack[found - 1] === " ") score += 5;
    score += 1;
    previous = found;
    cursor = found + 1;
  }
  // Prefer shorter labels when the score ties: "Rent" should beat "Recent rentals" for "rent".
  return score - haystack.length * 0.05;
}

export function CommandPalette() {
  const router = useRouter();
  const { signOut } = useAuth();
  const { resolved, setTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  const close = useCallback(() => setOpen(false), []);

  const commands = useMemo<Command[]>(() => {
    const go = (href: string) => () => {
      setOpen(false);
      router.push(href);
    };
    return [
      { id: "overview", label: "Overview", hint: "Balance and what's running", group: "Go to", run: go("/app") },
      { id: "rent", label: "Rent GPUs", hint: "Browse live capacity", group: "Go to", keywords: "buy book browse marketplace", run: go("/app/rent") },
      { id: "rentals", label: "My Rentals", hint: "Access keys and cancellations", group: "Go to", keywords: "orders history access", run: go("/app/rentals") },
      { id: "provide", label: "Provide", hint: "Fill demand or list capacity", group: "Go to", keywords: "sell supply listing", run: go("/app/provide") },
      { id: "billing", label: "Billing", hint: "Top up and review charges", group: "Go to", keywords: "tokens money wallet invoice", run: go("/app/billing") },
      { id: "landing", label: "Public site", group: "Go to", run: go("/") },
      { id: "diagnostics", label: "Diagnostics", hint: "Connectivity and config checks", group: "Go to", keywords: "debug health", run: go("/diagnostics") },
      {
        id: "theme",
        label: resolved === "dark" ? "Switch to light theme" : "Switch to dark theme",
        group: "Preferences",
        keywords: "dark light mode appearance",
        run: () => {
          setTheme(resolved === "dark" ? "light" : "dark");
          setOpen(false);
        },
      },
      {
        id: "theme-system",
        label: "Match system theme",
        group: "Preferences",
        keywords: "auto os appearance",
        run: () => {
          setTheme("system");
          setOpen(false);
        },
      },
      {
        id: "signout",
        label: "Sign out",
        group: "Account",
        run: () => {
          setOpen(false);
          void signOut();
        },
      },
    ];
  }, [router, resolved, setTheme, signOut]);

  const results = useMemo(() => {
    if (!query.trim()) return commands;
    return commands
      .map((c) => ({
        command: c,
        score: subsequenceScore(`${c.label} ${c.keywords ?? ""}`, query.trim()),
      }))
      .filter((r): r is { command: Command; score: number } => r.score !== null)
      .sort((a, b) => b.score - a.score)
      .map((r) => r.command);
  }, [commands, query]);

  // Group headers are derived here rather than tracked with a mutable cursor while mapping:
  // reassigning across a render is exactly the impurity the compiler forbids, and the answer
  // is a pure function of the list anyway — a row starts a group when it differs from the one
  // before it.
  const rows = useMemo(
    () =>
      results.map((command, index) => ({
        command,
        startsGroup: index === 0 || results[index - 1].group !== command.group,
      })),
    [results]
  );

  // Open/close on the global shortcut. Bound once, regardless of where focus is.
  //
  // The custom event is how non-keyboard entry points (the header's search button, and the
  // mobile bar where there is no keyboard at all) reach the palette. Going through an event
  // rather than lifting state keeps the palette self-contained — no provider to thread
  // through the tree just so a button can open a dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((o) => !o);
      }
    };
    const onRequest = () => setOpen(true);
    window.addEventListener("keydown", onKey);
    window.addEventListener(OPEN_EVENT, onRequest);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener(OPEN_EVENT, onRequest);
    };
  }, []);

  useEffect(() => {
    if (!open) {
      restoreFocusRef.current?.focus?.();
      return;
    }
    restoreFocusRef.current = document.activeElement as HTMLElement | null;
    setQuery("");
    setActive(0);
    const id = window.requestAnimationFrame(() => inputRef.current?.focus());
    return () => window.cancelAnimationFrame(id);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  // Clamp the highlight when filtering shrinks the list under it.
  useEffect(() => {
    setActive((a) => Math.min(a, Math.max(0, results.length - 1)));
  }, [results.length]);

  // Keep the highlighted row in view when arrowing past the fold.
  useEffect(() => {
    if (!open) return;
    const el = listRef.current?.children[active] as HTMLElement | undefined;
    el?.scrollIntoView({ block: "nearest" });
  }, [active, open]);

  if (!open) return null;

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      close();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => (results.length === 0 ? 0 : (a + 1) % results.length));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => (results.length === 0 ? 0 : (a - 1 + results.length) % results.length));
    } else if (e.key === "Enter") {
      e.preventDefault();
      results[active]?.run();
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-start justify-center px-4 pt-[12vh]">
      <button
        type="button"
        aria-label="Close command palette"
        onClick={close}
        className="absolute inset-0 animate-fade-in cursor-default bg-ink/40 backdrop-blur-[2px]"
      />

      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onKeyDown={onKeyDown}
        className="relative w-full max-w-lg animate-pop-in overflow-hidden rounded-2xl border border-line bg-surface shadow-lift"
      >
        <div className="flex items-center gap-3 border-b border-line px-4">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden className="shrink-0 text-ink-faint">
            <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="1.8" />
            <path d="m16.5 16.5 4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search pages and actions…"
            aria-label="Search pages and actions"
            className="min-h-14 w-full bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
          />
          <kbd className="hidden shrink-0 rounded border border-line bg-surface-sunken px-1.5 py-0.5 font-mono text-[10px] text-ink-muted sm:block">
            esc
          </kbd>
        </div>

        {results.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-ink-muted">
            Nothing matches “{query}”.
          </p>
        ) : (
          <ul ref={listRef} className="max-h-80 overflow-y-auto py-2">
            {rows.map(({ command: c, startsGroup }, i) => {
              return (
                <li key={c.id}>
                  {startsGroup && (
                    <p className="px-4 pb-1 pt-3 text-xs font-medium uppercase tracking-wider text-ink-faint">
                      {c.group}
                    </p>
                  )}
                  <button
                    onClick={c.run}
                    onMouseEnter={() => setActive(i)}
                    className={cx(
                      "flex w-full items-center justify-between gap-3 px-4 py-2.5 text-left transition-colors",
                      i === active ? "bg-brand-50" : "hover:bg-surface-sunken"
                    )}
                  >
                    <span className="min-w-0">
                      <span
                        className={cx(
                          "block truncate text-sm font-medium",
                          i === active ? "text-brand-700" : "text-ink"
                        )}
                      >
                        {c.label}
                      </span>
                      {c.hint && (
                        <span className="mt-0.5 block truncate text-xs text-ink-muted">
                          {c.hint}
                        </span>
                      )}
                    </span>
                    {i === active && (
                      <kbd className="hidden shrink-0 rounded border border-line bg-surface px-1.5 py-0.5 font-mono text-[10px] text-ink-muted sm:block">
                        ↵
                      </kbd>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
