"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useEvents } from "@/lib/events-context";
import type { AllocationResponse, OrderResponse } from "@/lib/types";
import { Button, ErrorNote, Badge, cx } from "@/components/ui";
import { LifecycleBadge } from "@/components/LifecycleBadge";
import { AccessPanel } from "@/components/AccessPanel";
import { CancelRental } from "@/components/CancelRental";
import { SlideOver } from "@/components/SlideOver";
import { Chips } from "@/components/BrowseControls";
import { CardGrid, CardSkeleton } from "@/components/ListingCard";
import type { Chip } from "@/lib/browse";
import { useNow } from "@/lib/useNow";
import { money, fmtWindow, fmtDuration, shortId } from "@/lib/format";

/**
 * Order history, browsed like the catalogue it came from.
 *
 * <p>Each rental used to be a full-width panel carrying its own access key and cancel control,
 * so five rentals meant five stacked consoles and a great deal of scrolling to find the one
 * that is actually running. Here the grid answers "what do I have and what state is it in",
 * and everything you can *do* to a rental lives in its sheet.
 */

const STATUS_CHIPS: Chip<AllocationResponse>[] = [
  { id: "all", label: "All", match: () => true },
  { id: "running", label: "Running", match: (r) => r.status !== "CANCELLED" && r.lifecycle === "RUNNING" },
  { id: "scheduled", label: "Scheduled", match: (r) => r.status !== "CANCELLED" && r.lifecycle === "SCHEDULED" },
  { id: "ended", label: "Ended", match: (r) => r.status !== "CANCELLED" && r.lifecycle === "ENDED" },
  { id: "cancelled", label: "Cancelled", match: (r) => r.status === "CANCELLED" },
];

export default function RentalsPage() {
  const { events } = useEvents();
  const [rentals, setRentals] = useState<AllocationResponse[] | null>(null);
  const [openOrders, setOpenOrders] = useState<OrderResponse[]>([]);
  const [chip, setChip] = useState("all");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const refresh = useCallback(async () => {
    try {
      const [r, o] = await Promise.all([api.myRentals(), api.myOrders("BUY")]);
      setRentals(r);
      // Only orders still waiting for a match matter here; filled ones are already
      // represented by the rentals above.
      setOpenOrders(o.filter((x) => x.remainingQuantity > 0 && x.status !== "CANCELLED"));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (events.length > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events[0]?.id]);

  const counts = useMemo(() => {
    if (rentals === null) return undefined;
    return Object.fromEntries(
      STATUS_CHIPS.map((c) => [c.id, rentals.filter((r) => c.match(r, now)).length])
    ) as Record<string, number>;
  }, [rentals, now]);

  const visible = useMemo(() => {
    if (rentals === null) return [];
    const match = (STATUS_CHIPS.find((c) => c.id === chip) ?? STATUS_CHIPS[0]).match;
    return rentals.filter((r) => match(r, now));
  }, [rentals, chip, now]);

  // Read from the live list rather than captured at click time, so the sheet reflects a
  // cancellation or a lifecycle change that arrived over the socket while it was open.
  const selected = useMemo(
    () => visible.find((r) => r.id === selectedId) ?? rentals?.find((r) => r.id === selectedId) ?? null,
    [visible, rentals, selectedId]
  );

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="page-title">My Rentals</h1>
          <p className="page-subtitle">Compute you&apos;ve secured, and orders still waiting to fill.</p>
        </div>
        <Button variant="secondary" onClick={() => void refresh()}>
          Refresh
        </Button>
      </header>

      <ErrorNote message={error} />

      <Chips chips={STATUS_CHIPS} active={chip} counts={counts} onSelect={setChip} />

      {rentals === null ? (
        <CardGrid>
          {Array.from({ length: 4 }, (_, i) => (
            <CardSkeleton key={i} />
          ))}
        </CardGrid>
      ) : visible.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-line-strong bg-surface px-6 py-16 text-center">
          <p className="text-base font-medium text-ink">
            {rentals.length === 0 ? "Nothing rented yet." : "Nothing in this state."}
          </p>
          {rentals.length === 0 && (
            <Link
              href="/app/rent"
              className="mt-3 inline-block text-sm font-medium text-brand-600 transition hover:text-brand-700"
            >
              Browse available GPUs →
            </Link>
          )}
        </div>
      ) : (
        <CardGrid>
          {visible.map((r) => (
            <RentalTile key={r.id} rental={r} onSelect={() => setSelectedId(r.id)} />
          ))}
        </CardGrid>
      )}

      {openOrders.length > 0 && (
        <section className="rounded-2xl border border-line bg-surface p-4 shadow-card sm:p-5">
          <h2 className="text-base font-semibold tracking-tight text-ink">Waiting for supply</h2>
          <p className="mt-0.5 text-sm text-ink-muted">Buy orders resting on the book</p>
          <ul className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {openOrders.map((o) => (
              <li key={o.id} className="rounded-xl border border-line px-4 py-3.5">
                <div className="flex items-center justify-between gap-2">
                  <p className="tnum text-sm font-medium text-ink">
                    {o.remainingQuantity} of {o.quantity} unfilled
                  </p>
                  <Badge className="shrink-0 border-brand-200 bg-brand-50 text-brand-700">
                    {o.status === "PARTIALLY_FILLED" ? "Partial" : "Open"}
                  </Badge>
                </div>
                <p className="mt-1 text-sm text-ink-muted">
                  {fmtWindow(o.windowStart, o.windowEnd)}
                </p>
                <p className="tnum mt-1 text-xs text-ink-faint">
                  up to {money(o.pricePerGpuHour)}/GPU-hr
                </p>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-sm leading-relaxed text-ink-faint">
            These match automatically when a provider lists compatible capacity, or when a
            provider fills them directly. Nothing is charged until a match happens.
          </p>
        </section>
      )}

      <SlideOver
        open={selected !== null}
        onClose={() => setSelectedId(null)}
        title={
          selected ? `${selected.quantity} GPU${selected.quantity === 1 ? "" : "s"}` : "Rental"
        }
        subtitle={selected ? fmtWindow(selected.windowStart, selected.windowEnd) : undefined}
      >
        {selected && <RentalDetail rental={selected} onChanged={refresh} />}
      </SlideOver>
    </div>
  );
}

/** Compact card: state and cost at a glance, no controls. */
function RentalTile({
  rental,
  onSelect,
}: {
  rental: AllocationResponse;
  onSelect: () => void;
}) {
  const cancelled = rental.status === "CANCELLED";
  const running = !cancelled && rental.lifecycle === "RUNNING";

  return (
    <button
      type="button"
      onClick={onSelect}
      className={cx(
        "group flex w-full flex-col gap-3 rounded-2xl border bg-surface p-4 text-left shadow-card transition-all duration-200 hover:-translate-y-0.5 hover:shadow-lift focus:outline-none focus-visible:ring-4 focus-visible:ring-brand-100",
        running ? "border-positive/30" : "border-line hover:border-line-strong",
        cancelled && "opacity-60"
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="tnum text-2xl font-semibold tracking-tight text-ink">
          {rental.quantity}
          <span className="ml-1 text-sm font-normal text-ink-muted">
            GPU{rental.quantity === 1 ? "" : "s"}
          </span>
        </span>
        {cancelled ? (
          <Badge className="border-line bg-surface-sunken text-ink-muted">Cancelled</Badge>
        ) : (
          <LifecycleBadge lifecycle={rental.lifecycle} />
        )}
      </div>

      <p className="text-sm leading-snug text-ink-soft">
        {fmtWindow(rental.windowStart, rental.windowEnd)}
      </p>

      <div className="mt-auto flex items-center justify-between border-t border-line pt-3">
        <span className="tnum text-xs text-ink-muted">
          {money(rental.executionPrice)}/GPU-hr
        </span>
        <span className="tnum text-sm font-semibold text-ink">{money(rental.maxCost)}</span>
      </div>
    </button>
  );
}

/**
 * Everything you can do to one rental.
 *
 * <p>There is deliberately no "report usage" control. Billing is per booked window, charged
 * once when the rental is created — asking the buyer to self-report hours was a fake input
 * that changed what they owed, which no real marketplace would expose.
 */
function RentalDetail({
  rental,
  onChanged,
}: {
  rental: AllocationResponse;
  onChanged: () => void;
}) {
  const cancelled = rental.status === "CANCELLED";

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between gap-3">
        {cancelled ? (
          <Badge className="border-line bg-surface-sunken text-ink-muted">Cancelled</Badge>
        ) : (
          <LifecycleBadge lifecycle={rental.lifecycle} />
        )}
        <span className="tnum text-xl font-semibold text-ink">{money(rental.maxCost)}</span>
      </div>

      <dl className="space-y-2.5 rounded-xl border border-line bg-surface-sunken p-4 text-sm">
        <Row label="Window" value={fmtWindow(rental.windowStart, rental.windowEnd)} />
        <Row label="Duration" value={fmtDuration(rental.windowSeconds)} />
        <Row label="Price" value={`${money(rental.executionPrice)} / GPU-hr`} />
        <Row label="Charged" value={money(rental.maxCost)} />
        {rental.refundedAmount != null && rental.refundedAmount > 0 && (
          <Row label="Refunded" value={money(rental.refundedAmount)} />
        )}
        <Row label="Allocation" value={shortId(rental.id, 18)} mono />
      </dl>

      {/* Access state: "check back at 3pm", the live key, or "ended". Polls on its own. */}
      {!cancelled && <AccessPanel allocationId={rental.id} />}

      <CancelRental allocationId={rental.id} onCancelled={onChanged} />
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0 text-ink-muted">{label}</dt>
      <dd className={cx("text-right text-ink", mono ? "font-mono text-xs" : "tnum")}>{value}</dd>
    </div>
  );
}
