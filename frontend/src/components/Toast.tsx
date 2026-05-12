"use client";

import type { RealtimeEvent } from "@/lib/types";
import { eventMeta } from "@/lib/eventMeta";
import { fmtTime } from "@/lib/format";
import { cx } from "./ui";

/**
 * Bottom-right stack of transient cards, one per recently arrived realtime event.
 * Auto-dismissal is driven by the EventsProvider; clicking the × removes one early.
 */
export function ToastStack({
  toasts,
  onDismiss,
}: {
  toasts: RealtimeEvent[];
  onDismiss: (id: string) => void;
}) {
  return (
    <div
      className="pointer-events-none fixed inset-x-3 bottom-3 z-50 flex flex-col gap-2.5 sm:left-auto sm:right-5 sm:w-80"
      role="region"
      aria-label="Notifications"
      aria-live="polite"
    >
      {toasts.map((event) => {
        const meta = eventMeta(event.type);
        return (
          <div
            key={event.id}
            className="pointer-events-auto animate-slide-in rounded-xl border border-line bg-surface p-4 shadow-lift"
          >
            <div className="flex items-center justify-between gap-2">
              <span
                className={cx(
                  "inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium",
                  meta.badge
                )}
              >
                <span className={cx("h-1.5 w-1.5 rounded-full", meta.dot)} />
                {meta.label}
              </span>
              <button
                onClick={() => onDismiss(event.id)}
                className="flex h-11 w-11 items-center justify-center text-lg leading-none text-ink-faint transition hover:text-ink"
                aria-label="Dismiss"
              >
                ×
              </button>
            </div>
            <p className="mt-2 text-sm leading-snug text-ink-soft">{event.message}</p>
            <p className="tnum mt-1.5 text-xs text-ink-faint">
              {fmtTime(event.createdAt)}
            </p>
          </div>
        );
      })}
    </div>
  );
}
