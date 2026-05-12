"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { CancellationQuote } from "@/lib/types";
import { Button } from "./ui";
import { money, fmtDuration } from "@/lib/format";

/**
 * Cancel control with an up-front refund quote.
 *
 * <p>The quote is fetched before the buyer commits and shown inside the confirmation, because
 * "you'll get $12 of $24 back" is a materially different decision from a bare "cancel?". The
 * refund depends on notice given, so the amount is read from the server at the moment of
 * asking rather than computed in the client where it could drift from the policy.
 */
export function CancelRental({
  allocationId,
  onCancelled,
}: {
  allocationId: string;
  onCancelled: () => void;
}) {
  const [quote, setQuote] = useState<CancellationQuote | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<CancellationQuote | null>(null);

  const loadQuote = useCallback(async () => {
    try {
      setQuote(await api.cancellationQuote(allocationId));
    } catch {
      /* A missing quote just hides the control; nothing actionable for the user. */
    }
  }, [allocationId]);

  useEffect(() => {
    void loadQuote();
  }, [loadQuote]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const res = await api.cancelRental(allocationId);
      setDone(res);
      setConfirming(false);
      onCancelled();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not cancel");
    } finally {
      setBusy(false);
    }
  }

  if (done || quote?.alreadyCancelled) {
    const refunded = done?.refundAmount ?? 0;
    return (
      <div className="mt-4 rounded-xl border border-line bg-surface-sunken px-4 py-3">
        <p className="text-sm font-medium text-ink-soft">Cancelled</p>
        {done && (
          <p className="mt-1 text-sm text-ink-muted">
            {refunded > 0
              ? `${money(refunded)} refunded to your balance.`
              : "No refund — cancelled inside the 4-hour window."}
          </p>
        )}
      </div>
    );
  }

  if (!quote || !quote.cancellable) return null;

  const tone =
    quote.tier === "FULL"
      ? "border-positive/25 bg-positive/[0.06]"
      : quote.tier === "PARTIAL"
        ? "border-caution/25 bg-caution/[0.07]"
        : "border-line bg-surface-sunken";

  if (!confirming) {
    return (
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
        <p className="text-xs text-ink-faint">
          {quote.noticeSeconds > 0
            ? `${fmtDuration(quote.noticeSeconds)} until your window opens`
            : "Window has started"}
          {" · "}
          <span
            className={
              quote.tier === "FULL"
                ? "text-positive"
                : quote.tier === "PARTIAL"
                  ? "text-caution"
                  : "text-ink-muted"
            }
          >
            {quote.tier === "FULL"
              ? "full refund available"
              : quote.tier === "PARTIAL"
                ? "50% refund available"
                : "no refund available"}
          </span>
        </p>
        <Button variant="ghost" size="sm" onClick={() => void setConfirming(true)}>
          Cancel rental
        </Button>
      </div>
    );
  }

  return (
    <div className={`mt-4 rounded-xl border p-4 ${tone}`}>
      <p className="font-medium text-ink">Cancel this rental?</p>
      <p className="mt-1.5 text-sm leading-relaxed text-ink-soft">{quote.explanation}</p>

      <dl className="mt-3 space-y-1.5 text-sm">
        <div className="flex justify-between">
          <dt className="text-ink-muted">You paid</dt>
          <dd className="tnum text-ink">{money(quote.bookingCharge)}</dd>
        </div>
        <div className="flex justify-between border-t border-line pt-1.5">
          <dt className="font-medium text-ink">You get back</dt>
          <dd
            className={
              quote.refundAmount > 0
                ? "tnum font-semibold text-positive"
                : "tnum font-semibold text-ink-muted"
            }
          >
            {money(quote.refundAmount)}
          </dd>
        </div>
      </dl>

      {error && <p className="mt-3 text-sm text-negative">{error}</p>}

      <div className="mt-4 flex flex-wrap gap-2">
        <Button variant="danger" size="sm" disabled={busy} onClick={() => void confirm()}>
          {busy ? "Cancelling…" : `Cancel and refund ${money(quote.refundAmount)}`}
        </Button>
        <Button variant="secondary" size="sm" onClick={() => setConfirming(false)}>
          Keep rental
        </Button>
      </div>
    </div>
  );
}
