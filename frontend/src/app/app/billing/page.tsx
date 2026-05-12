"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useEvents } from "@/lib/events-context";
import type { BalanceResponse, ChargeType, UsageLedgerEntry } from "@/lib/types";
import { Button, ErrorNote, StatCard, Badge, cx } from "@/components/ui";
import { SlideOver } from "@/components/SlideOver";
import { Chips } from "@/components/BrowseControls";
import type { Chip } from "@/lib/browse";
import { useNow } from "@/lib/useNow";
import { money, tokens, fmtDateTime, fmtDuration, shortId } from "@/lib/format";

const PRESETS = [25, 100, 500];

const LEDGER_CHIPS: Chip<UsageLedgerEntry>[] = [
  { id: "all", label: "All", match: () => true },
  { id: "booking", label: "Bookings", match: (e) => e.chargeType === "BOOKING" },
  { id: "refund", label: "Refunds", match: (e) => e.chargeType === "REFUND" },
  { id: "usage", label: "Metered", match: (e) => e.chargeType === "USAGE" },
];

/**
 * The wallet.
 *
 * <p>Adding tokens moved into a sheet. It is a two-field action taken rarely, and as a card
 * pinned above the ledger it pushed the history — the thing you actually come here to read —
 * below the fold on every visit.
 */
export default function BillingPage() {
  const { events } = useEvents();
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [ledger, setLedger] = useState<UsageLedgerEntry[] | null>(null);
  const [chip, setChip] = useState("all");
  const [topUpOpen, setTopUpOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const refresh = useCallback(async () => {
    try {
      const [b, l] = await Promise.all([api.myBalance(), api.myUsage()]);
      setBalance(b);
      setLedger(l);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load billing data");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (events.length > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events[0]?.id]);

  // Memoised because the ledger-derived lists below depend on it; a fresh [] literal on
  // every render would invalidate those useMemos each time and defeat the point of them.
  const rows = useMemo(() => ledger ?? [], [ledger]);

  // Net of refunds: BOOKING rows are positive, REFUND rows negative, USAGE rows zero.
  const totalSpent = rows.reduce((sum, e) => sum + Number(e.tokenCost), 0);
  // Hours actually booked, which is what was paid for. USAGE rows are metering only and
  // REFUND rows carry no duration, so counting either would double or distort the total.
  const totalSeconds = rows
    .filter((e) => e.chargeType === "BOOKING")
    .reduce((sum, e) => sum + e.usageSeconds, 0);

  const counts = useMemo(
    () =>
      ledger === null
        ? undefined
        : (Object.fromEntries(
            LEDGER_CHIPS.map((c) => [c.id, ledger.filter((e) => c.match(e, now)).length])
          ) as Record<string, number>),
    [ledger, now]
  );

  const visible = useMemo(() => {
    const match = (LEDGER_CHIPS.find((c) => c.id === chip) ?? LEDGER_CHIPS[0]).match;
    return rows.filter((e) => match(e, now));
  }, [rows, chip, now]);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="page-title">Billing</h1>
          <p className="page-subtitle">
            Prepaid tokens. Rentals are charged in full when booked, and refunded by how much
            notice you give if you cancel.
          </p>
        </div>
        <Button onClick={() => setTopUpOpen(true)}>Add tokens</Button>
      </header>

      <ErrorNote message={error} />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          label="Balance"
          value={balance ? money(balance.balance) : "—"}
          tone={balance && balance.balance <= 0 ? "bad" : "brand"}
        />
        <StatCard label="Total spent" value={money(totalSpent)} hint="net of refunds" />
        <StatCard
          label="Compute booked"
          value={fmtDuration(totalSeconds)}
          hint="Total window time paid for"
        />
      </div>

      <Chips chips={LEDGER_CHIPS} active={chip} counts={counts} onSelect={setChip} />

      <section className="overflow-hidden rounded-2xl border border-line bg-surface shadow-card">
        {ledger === null ? (
          <div className="divide-y divide-line">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="flex items-center justify-between gap-4 px-4 py-4 sm:px-5">
                <div className="flex-1 space-y-2">
                  <div className="h-4 w-32 animate-pulse rounded bg-surface-sunken" />
                  <div className="h-3 w-48 animate-pulse rounded bg-surface-sunken" />
                </div>
                <div className="h-5 w-16 animate-pulse rounded bg-surface-sunken" />
              </div>
            ))}
          </div>
        ) : visible.length === 0 ? (
          <div className="px-6 py-16 text-center">
            <p className="text-base font-medium text-ink">
              {rows.length === 0 ? "Nothing billed yet." : "Nothing of this type."}
            </p>
            {rows.length === 0 && (
              <p className="mt-1.5 text-sm text-ink-muted">
                Charges appear here when you book a rental.
              </p>
            )}
          </div>
        ) : (
          <ul className="divide-y divide-line">
            {visible.map((e) => {
              const cost = Number(e.tokenCost);
              return (
                <li
                  key={e.id}
                  className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-4 transition hover:bg-surface-muted sm:px-5"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <ChargeBadge type={e.chargeType} />
                      <span className="text-sm text-ink-soft">{fmtDateTime(e.createdAt)}</span>
                    </div>
                    <p className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-ink-faint">
                      <span className="font-mono">{shortId(e.allocationId, 12)}</span>
                      {e.chargeType !== "REFUND" && (
                        <>
                          <span aria-hidden>·</span>
                          <span className="tnum">{fmtDuration(e.usageSeconds)}</span>
                        </>
                      )}
                    </p>
                  </div>
                  <span
                    className={cx(
                      "tnum shrink-0 text-sm font-medium",
                      cost < 0 ? "text-positive" : cost === 0 ? "text-ink-faint" : "text-ink"
                    )}
                  >
                    {cost < 0 ? `+${tokens(-cost)}` : tokens(cost)}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <TopUpSheet open={topUpOpen} onClose={() => setTopUpOpen(false)} onDone={refresh} />
    </div>
  );
}

/** Distinguishes money movements from metering, which is always zero-cost. */
function ChargeBadge({ type }: { type: ChargeType }) {
  const style = {
    BOOKING: { className: "border-brand-200 bg-brand-50 text-brand-700", label: "Booking" },
    REFUND: { className: "border-positive/25 bg-positive/[0.08] text-positive", label: "Refund" },
    USAGE: { className: "border-line bg-surface-sunken text-ink-muted", label: "Metered" },
  }[type] ?? { className: "border-line bg-surface-sunken text-ink-muted", label: type };
  return <Badge className={style.className}>{style.label}</Badge>;
}

function TopUpSheet({
  open,
  onClose,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const [amount, setAmount] = useState("100");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setOk(null);
  }, [open]);

  async function topUp(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setOk(null);
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) {
      setError("Enter an amount greater than zero");
      return;
    }
    setBusy(true);
    try {
      const res = await api.topUp({ amount: value });
      setOk(`Added ${money(value)} — balance is now ${money(res.balance)}`);
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Top-up failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SlideOver
      open={open}
      onClose={onClose}
      title="Add tokens"
      subtitle="No payment processor wired up — tokens are credited directly"
      footer={
        <Button type="submit" form="topup-form" disabled={busy} className="w-full">
          {busy ? "Adding…" : `Add ${money(Number(amount) || 0)}`}
        </Button>
      }
    >
      <form id="topup-form" onSubmit={topUp} className="space-y-5">
        <div className="grid grid-cols-3 gap-2.5">
          {PRESETS.map((p) => {
            const active = Number(amount) === p;
            return (
              <button
                key={p}
                type="button"
                onClick={() => setAmount(String(p))}
                className={cx(
                  "tnum min-h-11 rounded-xl border text-sm font-medium transition",
                  active
                    ? "border-brand-600 bg-brand-50 text-brand-700"
                    : "border-line-strong bg-surface text-ink hover:border-ink-faint hover:bg-surface-muted"
                )}
              >
                {money(p)}
              </button>
            );
          })}
        </div>

        <label className="block">
          <span className="mb-1.5 block text-sm font-medium text-ink-soft">Amount</span>
          <div className="relative">
            <span className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-ink-muted">
              $
            </span>
            {/*
              step="any": with step 0.01 and min 0.0001 the browser only accepts
              0.0001 + n*0.01, so ordinary values like 100 were rejected client-side.
            */}
            <input
              type="number"
              step="any"
              min="0.0001"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="tnum min-h-11 w-full rounded-xl border border-line-strong bg-surface py-2.5 pl-7 pr-3.5 text-sm text-ink transition focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-100"
            />
          </div>
        </label>

        <ErrorNote message={error} />
        {ok && (
          <p className="rounded-xl border border-positive/25 bg-positive/[0.06] px-4 py-3 text-sm text-positive">
            {ok}
          </p>
        )}
      </form>
    </SlideOver>
  );
}
