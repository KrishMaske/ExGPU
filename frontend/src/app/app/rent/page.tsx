"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import { useEvents } from "@/lib/events-context";
import type { BalanceResponse, PlaceOrderResponse, SupplyListing } from "@/lib/types";
import { Button, Field, Input, ErrorNote } from "@/components/ui";
import { SlideOver } from "@/components/SlideOver";
import { Chips, FilterBar } from "@/components/BrowseControls";
import { CardGrid, CardSkeleton, SupplyCard } from "@/components/ListingCard";
import { DEFAULT_FILTERS, filterSupply, SUPPLY_CHIPS, type BrowseFilters } from "@/lib/browse";
import { useNow } from "@/lib/useNow";
import { money, fmtWindow, localDatetimeIn } from "@/lib/format";
import {
  parseRentalDraft,
  rentalDraftForListing,
  toLocalInput,
  validateRentalDraft,
  type RentalDraft,
} from "@/lib/marketplace";

const HOUR_MS = 3_600_000;

/**
 * The rent catalogue.
 *
 * <p>Browsing and committing are two surfaces, not one scroll. The grid is for comparing —
 * every card is the same shape so price and size are read positionally — and the sheet is for
 * the one listing you picked. Previously both lived on the page at once, which meant the form
 * was always on screen demanding attention even while you were still shopping.
 *
 * <p>The custom request is still here, because this is a limit order book and "nothing matches
 * today" is a real answer: an unmatched request rests until supply arrives. It opens the same
 * sheet in a different mode rather than sitting under the grid.
 */
export default function RentPage() {
  return (
    <Suspense fallback={<GridSkeleton />}>
      <RentPageContent />
    </Suspense>
  );
}

type SheetState =
  | { mode: "closed" }
  | { mode: "listing"; draft: RentalDraft; listing: SupplyListing | null }
  | { mode: "custom"; draft: RentalDraft | null };

function RentPageContent() {
  const searchParams = useSearchParams();
  const incoming = useMemo(() => parseRentalDraft(searchParams), [searchParams]);
  const { events, marketVersion } = useEvents();

  const [listings, setListings] = useState<SupplyListing[] | null>(null);
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [filters, setFilters] = useState<BrowseFilters>(DEFAULT_FILTERS);
  const [sheet, setSheet] = useState<SheetState>({ mode: "closed" });
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const refresh = useCallback(async () => {
    try {
      const [l, b] = await Promise.all([api.marketSupply(), api.myBalance()]);
      setListings(l);
      setBalance(b);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load listings");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Arriving from a landing-page listing link: open straight into the sheet, so the deep link
  // lands on the decision rather than dropping you at the top of the catalogue.
  useEffect(() => {
    if (incoming) setSheet({ mode: "listing", draft: incoming, listing: null });
  }, [incoming]);

  // Two triggers: your own activity, and anyone else's. Without the second, a listing
  // someone else just took would sit here as rentable until a manual reload.
  useEffect(() => {
    if (events.length > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events[0]?.id]);

  useEffect(() => {
    if (marketVersion > 0) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [marketVersion]);

  const visible = useMemo(
    () => (listings === null ? [] : filterSupply(listings, filters, now)),
    [listings, filters, now]
  );

  const chipCounts = useMemo(() => {
    if (listings === null) return undefined;
    return Object.fromEntries(
      SUPPLY_CHIPS.map((c) => [c.id, listings.filter((l) => c.match(l, now)).length])
    ) as Record<string, number>;
  }, [listings, now]);

  const lowBalance = balance !== null && balance.balance <= 0;

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="page-title">Rent GPUs</h1>
          <p className="page-subtitle">Live capacity, ready to book.</p>
        </div>
        <Button variant="secondary" onClick={() => setSheet({ mode: "custom", draft: null })}>
          Request custom window
        </Button>
      </header>

      <ErrorNote message={error} />

      {lowBalance && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-caution/25 bg-caution/[0.07] px-5 py-4">
          <p className="text-sm text-caution">
            You can place orders with an empty balance, but compute is cut the moment billing
            starts. Top up first.
          </p>
          <Link
            href="/app/billing"
            className="rounded-full bg-caution px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
          >
            Add tokens
          </Link>
        </div>
      )}

      <Chips
        chips={SUPPLY_CHIPS}
        active={filters.chip}
        counts={chipCounts}
        onSelect={(chip) => setFilters((f) => ({ ...f, chip }))}
      />

      <FilterBar filters={filters} onChange={setFilters} resultCount={visible.length} />

      {listings === null ? (
        <GridSkeleton />
      ) : visible.length === 0 ? (
        <EmptyGrid
          narrowed={listings.length > 0}
          onReset={() => setFilters(DEFAULT_FILTERS)}
          onRequest={() => setSheet({ mode: "custom", draft: null })}
        />
      ) : (
        <CardGrid>
          {visible.map((l) => (
            <SupplyCard
              key={l.listingId}
              listing={l}
              startsSoon={Date.parse(l.windowStart) <= now + HOUR_MS}
              onSelect={() =>
                setSheet({ mode: "listing", draft: rentalDraftForListing(l), listing: l })
              }
            />
          ))}
        </CardGrid>
      )}

      <RentSheet
        state={sheet}
        onClose={() => setSheet({ mode: "closed" })}
        onPlaced={() => void refresh()}
      />
    </div>
  );
}

function GridSkeleton() {
  return (
    <CardGrid>
      {Array.from({ length: 8 }, (_, i) => (
        <CardSkeleton key={i} />
      ))}
    </CardGrid>
  );
}

function EmptyGrid({
  narrowed,
  onReset,
  onRequest,
}: {
  narrowed: boolean;
  onReset: () => void;
  onRequest: () => void;
}) {
  return (
    <div className="rounded-2xl border border-dashed border-line-strong bg-surface px-6 py-16 text-center">
      <p className="text-base font-medium text-ink">
        {narrowed ? "Nothing matches these filters." : "No capacity listed for a future window."}
      </p>
      <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-muted">
        {narrowed
          ? "Widen the filters, or place a request that rests on the book until something compatible is listed."
          : "You can still place a request — it rests on the order book and matches automatically when a provider lists something compatible."}
      </p>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {narrowed && (
          <Button variant="secondary" onClick={onReset}>
            Clear filters
          </Button>
        )}
        <Button onClick={onRequest}>Request compute</Button>
      </div>
    </div>
  );
}

/**
 * The commit step.
 *
 * <p>Both modes place the same BUY order; they differ only in how much is prefilled and in what
 * the copy promises. Picking a listing yields terms guaranteed to match it, so the sheet can
 * say the booking is immediate. A custom request cannot promise that, and says so.
 */
function RentSheet({
  state,
  onClose,
  onPlaced,
}: {
  state: SheetState;
  onClose: () => void;
  onPlaced: () => void;
}) {
  const open = state.mode !== "closed";
  const fromListing = state.mode === "listing";
  const draft = state.mode === "closed" ? null : state.draft;

  const [quantity, setQuantity] = useState("1");
  const [maxPrice, setMaxPrice] = useState("3.00");
  const [start, setStart] = useState(() => localDatetimeIn(1));
  const [end, setEnd] = useState(() => localDatetimeIn(3));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PlaceOrderResponse | null>(null);

  // Adopt the selected listing's terms (or reset to sensible defaults for a custom request)
  // every time the sheet opens, so a previous booking never bleeds into the next one.
  useEffect(() => {
    if (!open) return;
    setResult(null);
    setError(null);
    if (draft) {
      setQuantity(String(draft.quantity));
      setMaxPrice(String(draft.maxPrice));
      setStart(toLocalInput(draft.start));
      setEnd(toLocalInput(draft.end));
    } else {
      setQuantity("1");
      setMaxPrice("3.00");
      setStart(localDatetimeIn(1));
      setEnd(localDatetimeIn(3));
    }
    // draft is a fresh object per open; keying on its values avoids a re-run loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, draft?.listingId, draft?.start, draft?.end, draft?.quantity, draft?.maxPrice]);

  const qty = Number(quantity) || 0;
  const price = Number(maxPrice) || 0;
  const hours =
    start && end ? Math.max(0, (new Date(end).getTime() - new Date(start).getTime()) / HOUR_MS) : 0;
  const maxSpend = qty * price * hours;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    setResult(null);
    try {
      const checked = validateRentalDraft({ quantity, maxPrice, start, end });
      if (!checked.draft) {
        setError(Object.values(checked.errors)[0] ?? "Check your request details.");
        return;
      }
      const res = await api.placeOrder({
        side: "BUY",
        pricePerGpuHour: checked.draft.maxPrice,
        quantity: checked.draft.quantity,
        startTime: checked.draft.start,
        endTime: checked.draft.end,
      });
      setResult(res);
      onPlaced();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not place order");
    } finally {
      setBusy(false);
    }
  }

  const listing = state.mode === "listing" ? state.listing : null;

  return (
    <SlideOver
      open={open}
      onClose={onClose}
      title={result ? "Order placed" : fromListing ? "Confirm rental" : "Request compute"}
      subtitle={
        result
          ? undefined
          : fromListing
            ? "Terms are prefilled from the listing you picked"
            : "Rests on the order book until matching supply appears"
      }
      footer={
        result ? (
          <Button className="w-full" onClick={onClose}>
            Done
          </Button>
        ) : (
          <div className="space-y-3">
            <div className="flex items-baseline justify-between text-sm">
              <span className="text-ink-muted">Maximum spend</span>
              <span className="tnum text-lg font-semibold text-ink">{money(maxSpend)}</span>
            </div>
            <Button
              type="submit"
              form="rent-form"
              disabled={busy || maxSpend <= 0}
              className="w-full"
            >
              {busy ? "Placing…" : fromListing ? "Confirm rental" : "Place request"}
            </Button>
          </div>
        )
      }
    >
      {result ? (
        <OrderReceipt result={result} />
      ) : (
        <form id="rent-form" onSubmit={submit} className="space-y-5">
          {listing && (
            <div className="rounded-xl border border-line bg-surface-sunken p-4">
              <div className="flex items-baseline justify-between">
                <span className="tnum text-2xl font-semibold text-ink">
                  {listing.availableGpus}
                </span>
                <span className="tnum text-sm font-medium text-brand-700">
                  {money(listing.pricePerGpuHour)} / GPU-hr
                </span>
              </div>
              <p className="mt-1 text-sm text-ink-muted">
                {fmtWindow(listing.windowStart, listing.windowEnd)}
              </p>
            </div>
          )}

          <ErrorNote message={error} />

          <Field label="GPUs" hint={listing ? `Up to ${listing.availableGpus} available` : undefined}>
            <Input
              type="number"
              min={1}
              max={listing?.availableGpus}
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </Field>

          <Field
            label="Max price per GPU-hour"
            hint="You are never charged more than the resting seller's price."
          >
            <Input
              type="number"
              step="0.01"
              min="0.01"
              value={maxPrice}
              onChange={(e) => setMaxPrice(e.target.value)}
            />
          </Field>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Start">
              <Input
                type="datetime-local"
                value={start}
                onChange={(e) => setStart(e.target.value)}
              />
            </Field>
            <Field label="End">
              <Input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
            </Field>
          </div>

          <p className="text-xs leading-relaxed text-ink-faint">
            {fromListing
              ? "These terms match the listing, so this books immediately."
              : "If nothing on the book matches, the request rests until a provider lists compatible capacity."}
          </p>
        </form>
      )}
    </SlideOver>
  );
}

function OrderReceipt({ result }: { result: PlaceOrderResponse }) {
  const matched = result.totalMatchedQuantity > 0;
  return (
    <div className="space-y-4">
      <div
        className={`rounded-xl border p-4 ${
          matched
            ? "border-positive/25 bg-positive/[0.06]"
            : "border-brand-200 bg-brand-50"
        }`}
      >
        <p className={`text-sm font-medium ${matched ? "text-positive" : "text-brand-700"}`}>
          {matched
            ? `Booked ${result.totalMatchedQuantity} GPU${result.totalMatchedQuantity === 1 ? "" : "s"}`
            : "Resting on the order book"}
        </p>
        <p className="mt-1 text-sm leading-relaxed text-ink-soft">
          {matched
            ? "Your rental is confirmed. Access opens when the window starts."
            : "Nothing on the book matched yet. This fills automatically as soon as a provider lists compatible capacity."}
        </p>
      </div>

      {result.allocations.length > 0 && (
        <ul className="space-y-2">
          {result.allocations.map((a) => (
            <li
              key={a.id}
              className="flex items-center justify-between gap-3 rounded-xl border border-line px-4 py-3"
            >
              <div className="min-w-0">
                <p className="tnum text-sm font-medium text-ink">
                  {a.quantity} GPU{a.quantity === 1 ? "" : "s"}
                </p>
                <p className="truncate text-xs text-ink-muted">
                  {fmtWindow(a.windowStart, a.windowEnd)}
                </p>
              </div>
              <span className="tnum shrink-0 text-sm font-medium text-ink">
                {money(a.executionPrice)}
              </span>
            </li>
          ))}
        </ul>
      )}

      <Link
        href="/app/rentals"
        className="block text-sm font-medium text-brand-600 transition hover:text-brand-700"
      >
        View my rentals →
      </Link>
    </div>
  );
}
