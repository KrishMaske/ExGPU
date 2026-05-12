"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useEvents } from "@/lib/events-context";
import type {
  AllocationResponse,
  DemandListing,
  OrderResponse,
  PlaceOrderResponse,
} from "@/lib/types";
import { Button, Field, Input, Empty, ErrorNote, Badge } from "@/components/ui";
import { LifecycleBadge } from "@/components/LifecycleBadge";
import { SlideOver } from "@/components/SlideOver";
import { Chips, FilterBar } from "@/components/BrowseControls";
import { CardGrid, CardSkeleton, DemandCard } from "@/components/ListingCard";
import { DEFAULT_FILTERS, DEMAND_CHIPS, filterDemand, type BrowseFilters } from "@/lib/browse";
import { useNow } from "@/lib/useNow";
import { money, fmtWindow, localDatetimeIn } from "@/lib/format";

const HOUR_MS = 3_600_000;

/**
 * Provider side, browsed the same way buyers browse supply.
 *
 * <p>Open demand leads because filling an existing request is a surer sale than listing into
 * an empty book and waiting: the buyer's price and window are already known, so the fill
 * matches on submission. Listing speculative capacity is the secondary action, and now opens
 * in a sheet rather than occupying the page above the thing you came to look at.
 */
export default function ProvidePage() {
  const { events, marketVersion } = useEvents();
  const [demand, setDemand] = useState<DemandListing[] | null>(null);
  const [listings, setListings] = useState<OrderResponse[]>([]);
  const [sold, setSold] = useState<AllocationResponse[]>([]);
  const [filters, setFilters] = useState<BrowseFilters>(DEFAULT_FILTERS);
  const [fillTarget, setFillTarget] = useState<DemandListing | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const refresh = useCallback(async () => {
    try {
      const [d, orders, supply] = await Promise.all([
        api.marketDemand(),
        api.myOrders("SELL"),
        api.mySupply(),
      ]);
      setDemand(d);
      setListings(orders);
      setSold(supply);
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

  // Someone else filling a request must remove it from the demand grid here too.
  useEffect(() => {
    if (marketVersion > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [marketVersion]);

  const visible = useMemo(
    () => (demand === null ? [] : filterDemand(demand, filters, now)),
    [demand, filters, now]
  );

  const chipCounts = useMemo(() => {
    if (demand === null) return undefined;
    return Object.fromEntries(
      DEMAND_CHIPS.map((c) => [c.id, demand.filter((d) => c.match(d, now)).length])
    ) as Record<string, number>;
  }, [demand, now]);

  const earnings = sold.reduce((sum, a) => sum + (a.maxCost ?? 0), 0);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="page-title">Provide</h1>
          <p className="page-subtitle">Fill an open request, or list idle GPU hours.</p>
        </div>
        <div className="flex items-end gap-5">
          <div className="text-right">
            <p className="text-xs font-medium uppercase tracking-wider text-ink-muted">
              Committed revenue
            </p>
            <p className="tnum text-2xl font-semibold text-positive">{money(earnings)}</p>
          </div>
          <Button onClick={() => setListOpen(true)}>List capacity</Button>
        </div>
      </header>

      <ErrorNote message={error} />

      <Chips
        chips={DEMAND_CHIPS}
        active={filters.chip}
        counts={chipCounts}
        onSelect={(chip) => setFilters((f) => ({ ...f, chip }))}
      />

      <FilterBar
        filters={filters}
        onChange={setFilters}
        resultCount={visible.length}
        priceLabel="Pays up to"
        sortPriceLabel="Price: best paying"
      />

      {demand === null ? (
        <CardGrid>
          {Array.from({ length: 8 }, (_, i) => (
            <CardSkeleton key={i} />
          ))}
        </CardGrid>
      ) : visible.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-line-strong bg-surface px-6 py-16 text-center">
          <p className="text-base font-medium text-ink">
            {demand.length > 0 ? "Nothing matches these filters." : "No open requests right now."}
          </p>
          <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-muted">
            List your capacity instead — it rests on the book and fills automatically when a
            buyer wants it.
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            {demand.length > 0 && (
              <Button variant="secondary" onClick={() => setFilters(DEFAULT_FILTERS)}>
                Clear filters
              </Button>
            )}
            <Button onClick={() => setListOpen(true)}>List capacity</Button>
          </div>
        </div>
      ) : (
        <CardGrid>
          {visible.map((d) => (
            <DemandCard
              key={d.requestId}
              request={d}
              startsSoon={Date.parse(d.windowStart) <= now + HOUR_MS}
              onSelect={() => setFillTarget(d)}
            />
          ))}
        </CardGrid>
      )}

      <MyListings listings={listings} sold={sold} />

      <FillSheet request={fillTarget} onClose={() => setFillTarget(null)} onFilled={refresh} />
      <ListSheet open={listOpen} onClose={() => setListOpen(false)} onPlaced={refresh} />
    </div>
  );
}

/** Your own side of the book, kept below the marketplace because it is reference, not action. */
function MyListings({
  listings,
  sold,
}: {
  listings: OrderResponse[];
  sold: AllocationResponse[];
}) {
  const [tab, setTab] = useState<"listings" | "sold">("listings");
  const items = tab === "listings" ? listings : sold;

  return (
    <section className="rounded-2xl border border-line bg-surface shadow-card">
      <header className="flex items-center gap-1 border-b border-line px-4 pt-3 sm:px-5">
        {(["listings", "sold"] as const).map((key) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            aria-current={tab === key ? "true" : undefined}
            className={`relative min-h-11 px-3 pb-3 text-sm font-medium transition-colors ${
              tab === key ? "text-brand-700" : "text-ink-muted hover:text-ink"
            }`}
          >
            {key === "listings" ? "Your listings" : "Sold capacity"}
            <span className="tnum ml-1.5 text-xs text-ink-faint">
              {key === "listings" ? listings.length : sold.length}
            </span>
            {tab === key && (
              <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-brand-600" />
            )}
          </button>
        ))}
      </header>

      <div className="p-4 sm:p-5">
        {items.length === 0 ? (
          <Empty>
            {tab === "listings"
              ? "You have not listed any capacity yet."
              : "Nothing sold yet."}
          </Empty>
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {tab === "listings"
              ? listings.map((o) => (
                  <li
                    key={o.id}
                    className="rounded-xl border border-line px-4 py-3.5 transition hover:border-line-strong"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="tnum text-base font-semibold text-ink">
                        {o.quantity} GPU{o.quantity === 1 ? "" : "s"}
                      </span>
                      <OrderStatusBadge status={o.status} />
                    </div>
                    <p className="mt-1 text-sm text-ink-muted">
                      {fmtWindow(o.windowStart, o.windowEnd)}
                    </p>
                    <div className="mt-2 flex items-center justify-between text-sm">
                      <span className="tnum font-medium text-brand-700">
                        {money(o.pricePerGpuHour)} / GPU-hr
                      </span>
                      <span className="tnum text-xs text-ink-faint">
                        {o.filledQuantity}/{o.quantity} filled
                      </span>
                    </div>
                  </li>
                ))
              : sold.map((a) => (
                  <li
                    key={a.id}
                    className="rounded-xl border border-line px-4 py-3.5 transition hover:border-line-strong"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="tnum text-base font-semibold text-ink">
                        {a.quantity} GPU{a.quantity === 1 ? "" : "s"}
                      </span>
                      <LifecycleBadge lifecycle={a.lifecycle} />
                    </div>
                    <p className="mt-1 text-sm text-ink-muted">
                      {fmtWindow(a.windowStart, a.windowEnd)}
                    </p>
                    <div className="mt-2 flex items-center justify-between text-sm">
                      <span className="tnum font-medium text-ink">
                        {money(a.executionPrice)} / GPU-hr
                      </span>
                      <span className="tnum text-xs font-medium text-positive">
                        {money(a.maxCost)}
                      </span>
                    </div>
                  </li>
                ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function OrderStatusBadge({ status }: { status: OrderResponse["status"] }) {
  const tone: Record<string, string> = {
    OPEN: "border-brand-200 bg-brand-50 text-brand-700",
    PARTIALLY_FILLED: "border-caution/25 bg-caution/[0.08] text-caution",
    FILLED: "border-positive/25 bg-positive/[0.08] text-positive",
    EXPIRED: "border-line bg-surface-sunken text-ink-muted",
    CANCELLED: "border-line bg-surface-sunken text-ink-muted",
  };
  return <Badge className={tone[status]}>{status.replace("_", " ").toLowerCase()}</Badge>;
}

/** Offer capacity into one open request. Quantity is capped at what the buyer actually wants. */
function FillSheet({
  request,
  onClose,
  onFilled,
}: {
  request: DemandListing | null;
  onClose: () => void;
  onFilled: () => void;
}) {
  const [gpus, setGpus] = useState("1");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PlaceOrderResponse | null>(null);

  useEffect(() => {
    if (!request) return;
    setGpus(String(request.gpusWanted));
    setError(null);
    setResult(null);
  }, [request]);

  const offered = request
    ? Math.min(Math.max(1, Number(gpus) || 0), request.gpusWanted)
    : 0;
  const earn = request ? (request.maxRevenue / request.gpusWanted) * offered : 0;

  async function fill() {
    if (!request) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.fillDemand(request.requestId, offered);
      setResult(res);
      onFilled();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not fill this request");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SlideOver
      open={request !== null}
      onClose={onClose}
      title={result ? "Capacity sold" : "Fill this request"}
      subtitle={result ? undefined : "You are quoting the buyer's own bid back at them"}
      footer={
        result ? (
          <Button className="w-full" onClick={onClose}>
            Done
          </Button>
        ) : (
          <div className="space-y-3">
            <div className="flex items-baseline justify-between text-sm">
              <span className="text-ink-muted">You earn up to</span>
              <span className="tnum text-lg font-semibold text-positive">{money(earn)}</span>
            </div>
            <Button className="w-full" disabled={busy || offered < 1} onClick={() => void fill()}>
              {busy ? "Filling…" : `Offer ${offered} GPU${offered === 1 ? "" : "s"}`}
            </Button>
          </div>
        )
      }
    >
      {!request ? null : result ? (
        <div className="rounded-xl border border-positive/25 bg-positive/[0.06] p-4">
          <p className="text-sm font-medium text-positive">
            Sold {result.totalMatchedQuantity} GPU
            {result.totalMatchedQuantity === 1 ? "" : "s"}
          </p>
          <p className="mt-1 text-sm leading-relaxed text-ink-soft">
            The buyer has been charged and access is provisioned for their window.
          </p>
        </div>
      ) : (
        <div className="space-y-5">
          <div className="rounded-xl border border-line bg-surface-sunken p-4">
            <div className="flex items-baseline justify-between">
              <span className="tnum text-2xl font-semibold text-ink">{request.gpusWanted}</span>
              <span className="tnum text-sm font-medium text-brand-700">
                {money(request.maxPricePerGpuHour)} / GPU-hr
              </span>
            </div>
            <p className="mt-1 text-sm text-ink-muted">
              {fmtWindow(request.windowStart, request.windowEnd)}
            </p>
          </div>

          <ErrorNote message={error} />

          <Field label="GPUs to offer" hint={`The buyer wants ${request.gpusWanted}`}>
            <Input
              type="number"
              min={1}
              max={request.gpusWanted}
              value={gpus}
              onChange={(e) => setGpus(e.target.value)}
            />
          </Field>

          <p className="text-xs leading-relaxed text-ink-faint">
            This places a SELL that mirrors the request&apos;s price and window, so it matches on
            submission rather than resting on the book.
          </p>
        </div>
      )}
    </SlideOver>
  );
}

/** Speculative listing: name a floor price and a window, and wait for a buyer. */
function ListSheet({
  open,
  onClose,
  onPlaced,
}: {
  open: boolean;
  onClose: () => void;
  onPlaced: () => void;
}) {
  const [quantity, setQuantity] = useState("4");
  const [price, setPrice] = useState("2.50");
  const [start, setStart] = useState(() => localDatetimeIn(1));
  const [end, setEnd] = useState(() => localDatetimeIn(9));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PlaceOrderResponse | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setResult(null);
  }, [open]);

  const qty = Number(quantity) || 0;
  const p = Number(price) || 0;
  const hours =
    start && end ? Math.max(0, (new Date(end).getTime() - new Date(start).getTime()) / HOUR_MS) : 0;
  const maxRevenue = qty * p * hours;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    setResult(null);
    try {
      const res = await api.placeOrder({
        side: "SELL",
        pricePerGpuHour: p,
        quantity: qty,
        startTime: new Date(start).toISOString(),
        endTime: new Date(end).toISOString(),
      });
      setResult(res);
      onPlaced();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create listing");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SlideOver
      open={open}
      onClose={onClose}
      title={result ? "Listing created" : "List capacity"}
      subtitle={result ? undefined : "Sets a floor price — the engine may fill you at a better one"}
      footer={
        result ? (
          <Button className="w-full" onClick={onClose}>
            Done
          </Button>
        ) : (
          <div className="space-y-3">
            <div className="flex items-baseline justify-between text-sm">
              <span className="text-ink-muted">Maximum revenue</span>
              <span className="tnum text-lg font-semibold text-positive">
                {money(maxRevenue)}
              </span>
            </div>
            <Button
              type="submit"
              form="list-form"
              disabled={busy || maxRevenue <= 0}
              className="w-full"
            >
              {busy ? "Listing…" : "Create listing"}
            </Button>
          </div>
        )
      }
    >
      {result ? (
        <div className="space-y-4">
          <div
            className={`rounded-xl border p-4 ${
              result.totalMatchedQuantity > 0
                ? "border-positive/25 bg-positive/[0.06]"
                : "border-brand-200 bg-brand-50"
            }`}
          >
            <p
              className={`text-sm font-medium ${
                result.totalMatchedQuantity > 0 ? "text-positive" : "text-brand-700"
              }`}
            >
              {result.totalMatchedQuantity > 0
                ? `Filled immediately — ${result.totalMatchedQuantity} GPU${
                    result.totalMatchedQuantity === 1 ? "" : "s"
                  } sold`
                : "Resting on the order book"}
            </p>
            <p className="mt-1 text-sm leading-relaxed text-ink-soft">
              {result.totalMatchedQuantity > 0
                ? "A buyer was already waiting at a compatible price."
                : "This fills automatically when a buyer wants capacity in your window."}
            </p>
          </div>
        </div>
      ) : (
        <form id="list-form" onSubmit={submit} className="space-y-5">
          <ErrorNote message={error} />

          <Field label="GPUs to list">
            <Input
              type="number"
              min="1"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </Field>

          <Field
            label="Floor price per GPU-hour"
            hint="You will never be filled below this"
          >
            {/*
              step="any": with step 0.01 and min 0.0001 the browser only accepts
              0.0001 + n*0.01, which rejects ordinary values like 2.50 client-side.
            */}
            <Input
              type="number"
              step="any"
              min="0.0001"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
            />
          </Field>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Available from">
              <Input
                type="datetime-local"
                value={start}
                onChange={(e) => setStart(e.target.value)}
              />
            </Field>
            <Field label="Available until">
              <Input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
            </Field>
          </div>
        </form>
      )}
    </SlideOver>
  );
}
