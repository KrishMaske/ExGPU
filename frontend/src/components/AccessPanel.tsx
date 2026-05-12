"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { AccessResponse } from "@/lib/types";
import { fmtDateTime, fmtDuration } from "@/lib/format";

/**
 * Live access state for one rental.
 *
 * <h3>Polling</h3>
 * Two independent clocks run here, and keeping them separate is the point:
 *
 * <ul>
 *   <li>A <b>local countdown</b> ticks every second purely in the browser. It never calls the
 *       API — it just decrements the number the server last gave us, so the display stays
 *       smooth without generating traffic.</li>
 *   <li>A <b>server poll</b> runs on a much slower cadence, and only near a boundary that
 *       actually matters. The backend endpoint is idempotent (a credential is stable within
 *       its 15-minute bucket), so polling is safe — but "safe" is not "free", and there is no
 *       reason to ask a question whose answer cannot have changed.</li>
 * </ul>
 *
 * The poll interval adapts: fast when the window is about to open or close, slow otherwise,
 * and stopped entirely once the rental reaches a terminal state.
 */
export function AccessPanel({ allocationId }: { allocationId: string }) {
  const [access, setAccess] = useState<AccessResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [revealed, setRevealed] = useState(false);

  // Locally-ticked mirror of the server's countdown, so the UI updates every second
  // without a request behind each tick.
  const [tick, setTick] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await api.rentalAccess(allocationId);
      setAccess(res);
      setTick(0);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load access state");
    }
  }, [allocationId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Local 1s clock. Pure display — no network.
  useEffect(() => {
    const id = window.setInterval(() => setTick((t) => t + 1), 1000);
    return () => window.clearInterval(id);
  }, []);

  // Adaptive server poll.
  useEffect(() => {
    if (!access) return;
    // Terminal states never change again; stop asking.
    if (access.state === "EXPIRED") return;

    const secondsToBoundary =
      access.state === "PENDING"
        ? (access.secondsUntilAvailable ?? 0) - tick
        : (access.secondsRemaining ?? 0) - tick;

    // Within a minute of the window opening or closing, check every 5s so the transition
    // lands promptly. Otherwise every 60s is plenty — nothing else can change the answer.
    const intervalMs = secondsToBoundary <= 60 ? 5000 : 60000;

    const id = window.setTimeout(() => void load(), intervalMs);
    return () => window.clearTimeout(id);
  }, [access, tick, load]);

  if (error) {
    return (
      <div className="mt-3 rounded-lg border border-negative/25 bg-negative/[0.06] px-4 py-3 text-sm text-negative">
        {error}
      </div>
    );
  }

  if (!access) {
    return (
      <div className="mt-3 rounded-lg border border-line bg-surface-sunken px-4 py-3 text-sm text-ink-muted">
        Checking access…
      </div>
    );
  }

  // Countdown derived locally from the last server value.
  const remaining = Math.max(
    0,
    (access.state === "PENDING"
      ? (access.secondsUntilAvailable ?? 0)
      : (access.secondsRemaining ?? 0)) - tick
  );

  if (access.state === "PENDING") {
    return (
      <Panel tone="pending">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <p className="text-sm font-medium text-brand-700">Access not open yet</p>
          <p className="tnum text-sm font-semibold text-brand-700">{fmtDuration(remaining)}</p>
        </div>
        <p className="mt-1.5 text-sm leading-relaxed text-ink-soft">
          Check back at{" "}
          <span className="font-medium text-brand-700">
            {fmtDateTime(access.windowStart)}
          </span>{" "}
          — your access key is issued when the window opens.
        </p>
        <p className="mt-2 font-mono text-xs text-ink-faint">
          node {access.nodeRef}
        </p>
      </Panel>
    );
  }

  if (access.state === "EXPIRED") {
    return (
      <Panel tone="ended">
        <p className="text-sm font-medium text-ink-soft">Rental ended</p>
        <p className="mt-1 text-sm text-ink-muted">
          The window closed at {fmtDateTime(access.windowEnd)}. Access keys are no longer
          issued for this rental.
        </p>
      </Panel>
    );
  }

  if (access.state === "REVOKED") {
    return (
      <Panel tone="revoked">
        <p className="text-sm font-medium text-negative">Access revoked</p>
        <p className="mt-1 text-sm text-ink-soft">{access.message}</p>
      </Panel>
    );
  }

  // ACTIVE
  const key = access.accessKey ?? "";
  return (
    <Panel tone="active">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="flex items-center gap-1.5 text-sm font-medium text-positive">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-positive" />
          Access open
        </p>
        <p className="tnum text-sm font-semibold text-positive">
          {fmtDuration(remaining)} left
        </p>
      </div>

      <div className="mt-3">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-medium uppercase tracking-wider text-ink-muted">
            Access key
          </span>
          <div className="flex gap-1.5">
            <button
              onClick={() => setRevealed((r) => !r)}
              className="rounded-full border border-line-strong px-2.5 py-1 text-xs font-medium text-ink-soft transition hover:border-ink-faint hover:text-ink"
            >
              {revealed ? "Hide" : "Reveal"}
            </button>
            <button
              onClick={() => {
                void navigator.clipboard.writeText(key);
                setCopied(true);
                window.setTimeout(() => setCopied(false), 1500);
              }}
              className="rounded-full border border-line-strong px-2.5 py-1 text-xs font-medium text-ink-soft transition hover:border-ink-faint hover:text-ink"
            >
              {copied ? "Copied" : "Copy"}
            </button>
          </div>
        </div>
        <p className="mt-1.5 break-all rounded-lg border border-line bg-surface-sunken px-3 py-2 font-mono text-xs text-ink">
          {revealed ? key : maskKey(key)}
        </p>
      </div>

      {access.connection && (
        <div className="mt-3 space-y-1 border-t border-positive/20 pt-3 font-mono text-xs text-ink-soft">
          <Row label="host" value={access.connection.host} />
          <Row label="port" value={String(access.connection.port)} />
          <Row label="user" value={access.connection.username} />
        </div>
      )}

      <p className="mt-3 text-xs leading-relaxed text-ink-faint">
        Key rotates at {fmtDateTime(access.keyExpiresAt)}; this panel fetches the current one
        automatically. Access ends for good at {fmtDateTime(access.windowEnd)}.
      </p>
    </Panel>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-ink-faint">{label}</span>
      <span className="truncate text-ink">{value}</span>
    </div>
  );
}

const TONES = {
  pending: "border-brand-200 bg-brand-50",
  active: "border-positive/25 bg-positive/[0.06]",
  ended: "border-line bg-surface-sunken",
  revoked: "border-negative/25 bg-negative/[0.06]",
} as const;

function Panel({
  tone,
  children,
}: {
  tone: keyof typeof TONES;
  children: React.ReactNode;
}) {
  return <div className={`mt-5 rounded-xl border p-4 ${TONES[tone]}`}>{children}</div>;
}

/** Shows enough of the key to recognise it without putting the whole secret on screen. */
function maskKey(key: string): string {
  if (key.length <= 24) return "•".repeat(key.length);
  return `${key.slice(0, 16)}${"•".repeat(24)}${key.slice(-6)}`;
}
