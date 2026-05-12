"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useEvents } from "@/lib/events-context";
import type { AllocationResponse, BalanceResponse } from "@/lib/types";
import { StatCard, Empty, ErrorNote, cx } from "@/components/ui";
import { LifecycleBadge } from "@/components/LifecycleBadge";
import { eventMeta } from "@/lib/eventMeta";
import { money, fmtWindow, fmtTime } from "@/lib/format";

/**
 * The home shelf.
 *
 * <p>Leads with the three things that decide what you can do next — balance, what is running,
 * what you are selling — then the shortcuts into each catalogue. Recent rentals are a preview
 * strip rather than a full list: the point is to notice something needs attention and click
 * through, not to manage it from here.
 */
export default function OverviewPage() {
  const { events } = useEvents();
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [rentals, setRentals] = useState<AllocationResponse[]>([]);
  const [supply, setSupply] = useState<AllocationResponse[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [b, r, s] = await Promise.all([api.myBalance(), api.myRentals(), api.mySupply()]);
      setBalance(b);
      setRentals(r);
      setSupply(s);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Any event reaching this user means their own data moved.
  useEffect(() => {
    if (events.length > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events[0]?.id]);

  const running = rentals.filter((r) => r.lifecycle === "RUNNING");
  const scheduled = rentals.filter((r) => r.lifecycle === "SCHEDULED");
  const lowBalance = balance !== null && balance.balance <= 0;

  return (
    <div className="space-y-6">
      <header>
        <h1 className="page-title">Overview</h1>
        <p className="page-subtitle">Your compute, your balance, and what&apos;s running right now.</p>
      </header>

      <ErrorNote message={error} />

      {lowBalance && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-caution/25 bg-caution/[0.07] px-5 py-4">
          <p className="text-sm text-caution">
            Your token balance is empty. Compute won&apos;t run until you top up.
          </p>
          <Link
            href="/app/billing"
            className="rounded-full bg-caution px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
          >
            Add tokens
          </Link>
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard
          label="Balance"
          value={balance ? money(balance.balance) : "—"}
          tone={lowBalance ? "bad" : "brand"}
          hint="Drawn down per second used"
        />
        <StatCard
          label="Running now"
          value={running.length}
          tone={running.length > 0 ? "good" : "default"}
          hint="Inside their window"
        />
        <StatCard label="Scheduled" value={scheduled.length} hint="Not started yet" />
        <StatCard label="Providing" value={supply.length} hint="Sold from your listings" />
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <ActionTile
          href="/app/rent"
          title="Rent GPUs"
          body="Browse live capacity and book a window."
          tone="brand"
        />
        <ActionTile
          href="/app/provide"
          title="Provide"
          body="Fill an open request or list idle hours."
          tone="positive"
        />
        <ActionTile
          href="/app/billing"
          title="Billing"
          body="Top up and review what you've been charged."
          tone="neutral"
        />
      </div>

      <div className="grid gap-5 lg:grid-cols-3">
        <section className="lg:col-span-2">
          <div className="mb-3 flex items-baseline justify-between">
            <h2 className="text-base font-semibold tracking-tight text-ink">Your rentals</h2>
            <Link
              href="/app/rentals"
              className="text-sm font-medium text-brand-600 transition hover:text-brand-700"
            >
              See all →
            </Link>
          </div>

          {rentals.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-line-strong bg-surface px-6 py-12 text-center">
              <p className="text-base text-ink">You&apos;re not renting any compute yet.</p>
              <Link
                href="/app/rent"
                className="mt-3 inline-block text-sm font-medium text-brand-600 transition hover:text-brand-700"
              >
                Browse available GPUs →
              </Link>
            </div>
          ) : (
            <ul className="grid gap-3 sm:grid-cols-2">
              {rentals.slice(0, 4).map((r) => (
                <li key={r.id}>
                  <Link
                    href="/app/rentals"
                    className="flex h-full flex-col gap-2.5 rounded-2xl border border-line bg-surface p-4 shadow-card transition-all duration-200 hover:-translate-y-0.5 hover:border-line-strong hover:shadow-lift"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="tnum text-xl font-semibold tracking-tight text-ink">
                        {r.quantity}
                        <span className="ml-1 text-sm font-normal text-ink-muted">
                          GPU{r.quantity === 1 ? "" : "s"}
                        </span>
                      </span>
                      <LifecycleBadge lifecycle={r.lifecycle} />
                    </div>
                    <p className="text-sm leading-snug text-ink-soft">
                      {fmtWindow(r.windowStart, r.windowEnd)}
                    </p>
                    <div className="mt-auto flex items-center justify-between border-t border-line pt-2.5 text-xs">
                      <span className="tnum text-ink-muted">
                        {money(r.executionPrice)}/GPU-hr
                      </span>
                      <span className="tnum font-medium text-ink">{money(r.maxCost)}</span>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section>
          <div className="mb-3 flex items-baseline justify-between">
            <h2 className="text-base font-semibold tracking-tight text-ink">Activity</h2>
            <span className="text-xs text-ink-faint">Your events only</span>
          </div>
          <div className="rounded-2xl border border-line bg-surface p-4 shadow-card sm:p-5">
            {events.length === 0 ? (
              <Empty>Nothing yet this session.</Empty>
            ) : (
              <ul className="space-y-4">
                {events.slice(0, 7).map((e) => {
                  const meta = eventMeta(e.type);
                  return (
                    <li key={e.id}>
                      <span
                        className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${meta.badge}`}
                      >
                        {meta.label}
                      </span>
                      <p className="mt-1.5 text-sm leading-snug text-ink-soft">{e.message}</p>
                      <p className="tnum mt-0.5 text-xs text-ink-faint">{fmtTime(e.createdAt)}</p>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

/** Entry point into one of the catalogues — the shelf tiles on the home screen. */
function ActionTile({
  href,
  title,
  body,
  tone,
}: {
  href: string;
  title: string;
  body: string;
  tone: "brand" | "positive" | "neutral";
}) {
  const accent = {
    brand: "bg-gradient-to-br from-brand-500 to-brand-700",
    positive: "bg-gradient-to-br from-positive to-[#0A6E49]",
    neutral: "bg-gradient-to-br from-ink-soft to-ink",
  }[tone];

  return (
    <Link
      href={href}
      className="group flex items-center gap-4 rounded-2xl border border-line bg-surface p-4 shadow-card transition-all duration-200 hover:-translate-y-0.5 hover:border-line-strong hover:shadow-lift"
    >
      <span className={cx("h-11 w-1.5 shrink-0 rounded-full", accent)} aria-hidden />
      <span className="min-w-0">
        <span className="block text-sm font-semibold text-ink">{title}</span>
        <span className="mt-0.5 block text-xs leading-snug text-ink-muted">{body}</span>
      </span>
      <span
        aria-hidden
        className="ml-auto shrink-0 text-ink-faint transition-transform duration-200 group-hover:translate-x-0.5 group-hover:text-ink-muted"
      >
        →
      </span>
    </Link>
  );
}
