"use client";

import { useCallback, useEffect, useRef } from "react";
import { cx } from "./ui";

/**
 * A sheet that slides in from the right, holding the step that used to be a form stacked
 * below the list.
 *
 * <p>Why a sheet and not a route: the browse grid is the thing being compared against, and
 * navigating away to confirm loses that context — you stop seeing the six other listings you
 * were weighing. The sheet keeps the grid on screen and dimmed, which is what makes a
 * catalogue feel like a catalogue rather than a wizard.
 *
 * <p>On phones it docks to the bottom and rises instead, because a 420px-wide panel on a
 * 390px-wide screen is just a worse full-screen modal.
 *
 * <h2>What this handles that a bare div would not</h2>
 * <ul>
 *   <li><b>Focus</b> — moves into the sheet on open, is trapped inside it while open, and
 *       returns to whatever opened it on close. Without the restore, closing the sheet drops
 *       the caret at the top of the document and a keyboard user loses their place in the grid.</li>
 *   <li><b>Escape</b> — closes, as every dialog should.</li>
 *   <li><b>Scroll lock</b> — the page behind cannot scroll, so dismissing the sheet does not
 *       leave you somewhere else in the list than you were.</li>
 * </ul>
 */
export function SlideOver({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  /** Pinned to the bottom of the sheet, outside the scroll area — where the CTA belongs. */
  footer?: React.ReactNode;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  // Remember the opener before focus moves, so it can be handed back on close.
  useEffect(() => {
    if (open) restoreFocusRef.current = document.activeElement as HTMLElement | null;
  }, [open]);

  const focusables = useCallback((): HTMLElement[] => {
    const root = panelRef.current;
    if (!root) return [];
    return Array.from(
      root.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )
    ).filter((el) => el.offsetParent !== null);
  }, []);

  useEffect(() => {
    if (!open) return;

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;

      // Cycle within the sheet. Without this, Tab walks out into the dimmed grid behind,
      // where the user cannot see where their focus went.
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose, focusables]);

  // Move focus in once the panel has mounted its content.
  useEffect(() => {
    if (!open) return;
    const id = window.requestAnimationFrame(() => {
      const items = focusables();
      (items[0] ?? panelRef.current)?.focus();
    });
    return () => window.cancelAnimationFrame(id);
  }, [open, focusables]);

  // Restore focus to the opener. Separate from the open effect so it runs on the way out.
  useEffect(() => {
    if (open) return;
    restoreFocusRef.current?.focus?.();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <button
        type="button"
        aria-label="Close panel"
        onClick={onClose}
        className="absolute inset-0 animate-fade-in cursor-default bg-ink/25 backdrop-blur-[2px]"
      />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={cx(
          "relative flex w-full flex-col bg-surface shadow-lift outline-none",
          // Phone: docked to the bottom, capped so the grid stays visible above it.
          "max-h-[88vh] animate-slide-up rounded-t-2xl",
          // Desktop: full-height rail on the right.
          "sm:h-full sm:max-h-none sm:w-[26rem] sm:animate-slide-over sm:rounded-none"
        )}
      >
        <header className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-base font-semibold tracking-tight text-ink">{title}</h2>
            {subtitle && <p className="mt-0.5 text-sm text-ink-muted">{subtitle}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-1.5 -mt-1 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-ink-muted transition hover:bg-surface-sunken hover:text-ink"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
              <path
                d="M4 4l8 8M12 4l-8 8"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
              />
            </svg>
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-5 py-5">{children}</div>

        {footer && (
          <footer className="border-t border-line bg-surface px-5 py-4">{footer}</footer>
        )}
      </div>
    </div>
  );
}
