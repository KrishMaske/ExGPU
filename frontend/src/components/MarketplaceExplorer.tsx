"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { fmtWindow, localDatetimeIn, money } from "@/lib/format";
import {
  listingMatches,
  rentalDraftForListing,
  rentHref,
  validateRentalDraft,
  type RentalDraft,
  type RentalErrors,
  type RentalField,
} from "@/lib/marketplace";
import { authHref } from "@/lib/navigation";
import type { SupplyListing } from "@/lib/types";

const fieldOrder: RentalField[] = ["quantity", "maxPrice", "start", "end"];

/**
 * How many listings the landing page will show before deferring to the full catalogue.
 *
 * <p>The landing page is a shop window, not the order book. Rendering every open listing here
 * made the page grow without bound as supply arrived, buried the sections below it, and gave
 * away the whole inventory to anonymous visitors — while /app/rent, which exists precisely to
 * browse and filter it properly, had nothing left to offer. A fixed-size rail keeps the page a
 * constant height no matter how deep the book gets.
 */
const RAIL_LIMIT = 7;

export function MarketplaceExplorer() {
  const { user } = useAuth();
  const [listings, setListings] = useState<SupplyListing[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [quantity, setQuantity] = useState("1");
  const [maxPrice, setMaxPrice] = useState("3.00");
  const [start, setStart] = useState(() => localDatetimeIn(1));
  const [end, setEnd] = useState(() => localDatetimeIn(3));
  const [errors, setErrors] = useState<RentalErrors>({});
  const [criteria, setCriteria] = useState<RentalDraft | null>(null);
  const refs = useRef<Partial<Record<RentalField, HTMLInputElement | null>>>({});

  useEffect(() => {
    let alive = true;
    let inFlight = false;
    const load = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const next = await api.marketSupply();
        if (alive) {
          setListings(
            [...next].sort(
              (a, b) =>
                a.pricePerGpuHour - b.pricePerGpuHour ||
                Date.parse(a.windowStart) - Date.parse(b.windowStart)
            )
          );
          setError(null);
        }
      } catch (err) {
        if (alive) setError(err instanceof Error ? err.message : "Could not load the marketplace.");
      } finally {
        inFlight = false;
      }
    };
    void load();
    const interval = window.setInterval(() => void load(), 30_000);
    return () => {
      alive = false;
      window.clearInterval(interval);
    };
  }, [reload]);

  const matching = useMemo(
    () => (listings ?? []).filter((listing) => !criteria || listingMatches(listing, criteria)),
    [criteria, listings]
  );
  const visible = matching.slice(0, RAIL_LIMIT);
  const overflow = matching.length - visible.length;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const checked = validateRentalDraft({ quantity, maxPrice, start, end });
    setErrors(checked.errors);
    if (!checked.draft) {
      const first = fieldOrder.find((field) => checked.errors[field]);
      if (first) refs.current[first]?.focus();
      return;
    }
    setCriteria(checked.draft);
    window.setTimeout(() => document.getElementById("inventory-results")?.focus(), 0);
  }

  function clear() {
    setQuantity("1");
    setMaxPrice("3.00");
    setStart(localDatetimeIn(1));
    setEnd(localDatetimeIn(3));
    setErrors({});
    setCriteria(null);
  }

  const providerHref = user ? "/app/provide" : authHref("/signup", "/app/provide");
  const browseHref = user ? "/app/rent" : authHref("/login", "/app/rent");

  return (
    <section id="marketplace" className="scroll-mt-28 pb-20 sm:pb-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <form
          onSubmit={submit}
          noValidate
          aria-label="Search GPU availability"
          className="relative z-10 -mt-4 grid gap-2 rounded-[2rem] border border-line bg-surface p-3 shadow-[0_18px_45px_rgba(48,32,80,.12)] md:grid-cols-[1.25fr_1.25fr_.7fr_.85fr_auto] md:rounded-full"
        >
          <SearchField field="start" label="Start" type="datetime-local" value={start} error={errors.start} setRef={(node) => { refs.current.start = node; }} onChange={setStart} />
          <SearchField field="end" label="End" type="datetime-local" value={end} error={errors.end} setRef={(node) => { refs.current.end = node; }} onChange={setEnd} />
          <SearchField field="quantity" label="GPUs" type="number" min="1" step="1" value={quantity} error={errors.quantity} setRef={(node) => { refs.current.quantity = node; }} onChange={setQuantity} />
          <SearchField field="maxPrice" label="Max $ / GPU-hour" type="number" min="0.0001" step="any" value={maxPrice} error={errors.maxPrice} setRef={(node) => { refs.current.maxPrice = node; }} onChange={setMaxPrice} />
          <button type="submit" className="min-h-12 rounded-full bg-brand-600 px-6 font-semibold text-white shadow-brand transition hover:bg-brand-700 md:self-center">
            Search
          </button>
        </form>

        <div className="mt-14 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[.16em] text-brand-600">
              Live marketplace
            </p>
            <h2 className="mt-2 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
              Available compute
            </h2>
            <p className="mt-2 text-ink-muted">
              {listings === null
                ? "Loading current listings…"
                : matching.length === 0
                  ? "Real listings, ordered from lowest price."
                  : `Showing ${visible.length} of ${matching.length} open listings, cheapest first.`}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {criteria && (
              <button
                type="button"
                onClick={clear}
                className="min-h-11 rounded-full border border-line-strong bg-surface px-5 text-sm font-semibold text-ink hover:border-brand-300 hover:bg-brand-50"
              >
                Clear filters
              </button>
            )}
            <Link
              href={browseHref}
              className="inline-flex min-h-11 items-center rounded-full border border-line-strong bg-surface px-5 text-sm font-semibold text-ink transition hover:border-brand-300 hover:bg-brand-50"
            >
              Browse all
            </Link>
          </div>
        </div>

        <div id="inventory-results" tabIndex={-1} className="mt-8 outline-none" aria-live="polite">
          {listings === null && !error ? (
            <Rail>
              <span className="sr-only">Loading marketplace inventory.</span>
              {Array.from({ length: 5 }).map((_, index) => (
                <div key={index} aria-hidden className="shimmer h-64 w-[19rem] shrink-0 snap-start rounded-3xl bg-surface-sunken" />
              ))}
            </Rail>
          ) : error ? (
            <StatePanel title="The marketplace is temporarily unavailable" text={error}>
              <button
                type="button"
                onClick={() => setReload((value) => value + 1)}
                className="mt-5 min-h-11 rounded-full bg-brand-600 px-5 font-semibold text-white"
              >
                Retry
              </button>
            </StatePanel>
          ) : visible.length === 0 ? (
            <StatePanel
              title={criteria ? "No exact matches yet" : "No capacity is listed yet"}
              text={
                criteria
                  ? "Try a wider time window, a smaller quantity, or a higher price ceiling."
                  : "Be the first provider to publish an available GPU window."
              }
            >
              {criteria ? (
                <button type="button" onClick={clear} className="mt-5 min-h-11 rounded-full bg-brand-600 px-5 font-semibold text-white">
                  Clear filters
                </button>
              ) : (
                <Link href={providerHref} className="mt-5 inline-flex min-h-11 items-center rounded-full bg-brand-600 px-5 font-semibold text-white">
                  List your GPUs
                </Link>
              )}
            </StatePanel>
          ) : (
            <Rail>
              {visible.map((listing) => {
                const destination = rentHref(rentalDraftForListing(listing, criteria));
                const href = user ? destination : authHref("/login", destination);
                return <RailCard key={listing.listingId} listing={listing} href={href} />;
              })}

              {/* Terminal card rather than a link below the rail: at the end of a horizontal
                  scroll it is exactly where someone who has run out of cards is already looking. */}
              <Link
                href={browseHref}
                className="flex w-[19rem] shrink-0 snap-start flex-col items-center justify-center gap-2 rounded-3xl border border-dashed border-line-strong bg-surface-muted p-6 text-center transition hover:border-brand-300 hover:bg-brand-50"
              >
                <span className="text-base font-semibold text-ink">
                  {overflow > 0 ? `${overflow} more listing${overflow === 1 ? "" : "s"}` : "Browse the full book"}
                </span>
                <span className="text-sm text-ink-muted">
                  Filter by price, size and window
                </span>
                <span aria-hidden className="mt-1 text-brand-600">→</span>
              </Link>
            </Rail>
          )}
        </div>
      </div>
    </section>
  );
}

/**
 * Horizontal scroller with snap points and arrow buttons.
 *
 * <p>Arrows are shown only when there is something to scroll to, and are hidden from assistive
 * tech: the rail is a plain scroll container, so keyboard and screen-reader users reach every
 * card by tabbing through the links inside it. The buttons are a mouse affordance, not the
 * only way through.
 */
function Rail({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [atStart, setAtStart] = useState(true);
  const [atEnd, setAtEnd] = useState(false);

  const sync = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    setAtStart(el.scrollLeft <= 2);
    setAtEnd(el.scrollLeft + el.clientWidth >= el.scrollWidth - 2);
  }, []);

  useEffect(() => {
    sync();
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(sync);
    observer.observe(el);
    return () => observer.disconnect();
  }, [sync]);

  const nudge = (direction: 1 | -1) => {
    const el = ref.current;
    if (!el) return;
    el.scrollBy({ left: direction * Math.max(280, el.clientWidth * 0.8), behavior: "smooth" });
  };

  return (
    <div className="relative">
      <div
        ref={ref}
        onScroll={sync}
        className="no-scrollbar -mx-4 flex snap-x snap-mandatory gap-5 overflow-x-auto px-4 pb-2 sm:mx-0 sm:px-0"
      >
        {children}
      </div>

      <RailButton side="left" hidden={atStart} onClick={() => nudge(-1)} />
      <RailButton side="right" hidden={atEnd} onClick={() => nudge(1)} />
    </div>
  );
}

function RailButton({
  side,
  hidden,
  onClick,
}: {
  side: "left" | "right";
  hidden: boolean;
  onClick: () => void;
}) {
  if (hidden) return null;
  return (
    <button
      type="button"
      aria-hidden
      tabIndex={-1}
      onClick={onClick}
      className={`absolute top-1/2 hidden h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full border border-line bg-surface text-ink shadow-lift transition hover:border-ink-faint lg:flex ${
        side === "left" ? "-left-5" : "-right-5"
      }`}
    >
      {side === "left" ? "‹" : "›"}
    </button>
  );
}

function RailCard({ listing, href }: { listing: SupplyListing; href: string }) {
  return (
    <article className="group flex w-[19rem] shrink-0 snap-start flex-col overflow-hidden rounded-3xl border border-line bg-surface shadow-card transition hover:-translate-y-0.5 hover:border-line-strong hover:shadow-lift">
      <div className="relative h-28 overflow-hidden bg-gradient-to-br from-brand-500 to-brand-700">
        <div
          aria-hidden
          className="absolute inset-0 opacity-30 [background-image:linear-gradient(rgba(255,255,255,.25)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,.25)_1px,transparent_1px)] [background-size:22px_22px]"
        />
        <div className="absolute inset-0 flex items-center justify-center leading-none text-white">
          <span className="tnum text-4xl font-semibold tracking-tight">{listing.availableGpus}</span>
          <span className="ml-1.5 text-sm font-medium text-white/80">
            GPU{listing.availableGpus === 1 ? "" : "s"}
          </span>
        </div>
        <span className="absolute left-3 top-3 rounded-full bg-white/90 px-2.5 py-1 text-xs font-semibold text-[#5B21B6] backdrop-blur-sm">
          {listing.windowHours}h window
        </span>
      </div>

      <div className="flex flex-1 flex-col gap-3 p-5">
        <div className="flex items-baseline justify-between gap-2">
          <p className="tnum text-lg font-semibold text-ink">
            {money(listing.pricePerGpuHour)}
            <span className="ml-1 text-xs font-normal text-ink-muted">/ GPU-hr</span>
          </p>
        </div>
        <p className="text-sm leading-snug text-ink-soft">
          {fmtWindow(listing.windowStart, listing.windowEnd)}
        </p>
        <div className="mt-auto flex items-end justify-between gap-3 border-t border-line pt-4">
          <div className="text-sm text-ink-muted">
            <p className="tnum font-medium text-ink">{money(listing.estimatedCostPerGpu)}</p>
            <p className="text-xs">full window / GPU</p>
          </div>
          <Link
            href={href}
            className="inline-flex min-h-11 shrink-0 items-center rounded-full bg-brand-600 px-5 text-sm font-semibold text-white shadow-brand transition hover:bg-brand-700"
          >
            Rent
          </Link>
        </div>
      </div>
    </article>
  );
}

function SearchField({
  field,
  label,
  error,
  setRef,
  onChange,
  ...props
}: {
  field: RentalField;
  label: string;
  error?: string;
  setRef: (node: HTMLInputElement | null) => void;
  onChange: (value: string) => void;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, "onChange">) {
  const errorId = `${field}-search-error`;
  return (
    <label className="min-w-0 rounded-2xl px-4 py-2 transition focus-within:bg-brand-50 md:rounded-full md:border-r md:border-line">
      <span className="block text-xs font-semibold text-ink">{label}</span>
      <input
        {...props}
        ref={setRef}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? errorId : undefined}
        className="mt-0.5 min-h-7 w-full bg-transparent text-sm text-ink outline-none"
      />
      {error && (
        <span id={errorId} className="mt-1 block text-xs text-negative">
          {error}
        </span>
      )}
    </label>
  );
}

function StatePanel({
  title,
  text,
  children,
}: {
  title: string;
  text: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-3xl border border-dashed border-line-strong bg-surface-muted px-6 py-14 text-center">
      <h3 className="text-xl font-semibold text-ink">{title}</h3>
      <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-ink-muted">{text}</p>
      {children}
    </div>
  );
}
