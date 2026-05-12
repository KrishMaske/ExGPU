"use client";

import type { DemandListing, SupplyListing } from "@/lib/types";
import { fmtWindow, money } from "@/lib/format";
import { cx } from "./ui";

/**
 * Catalogue cards for the browse grids.
 *
 * <p>A marketplace card normally leads with a photograph. There are no photographs here — the
 * backend exposes quantity, price and window and nothing else — so the GPU count takes the
 * hero slot instead. It is the number people scan for, and rendering it at display size gives
 * the grid the visual rhythm the missing image would have provided.
 *
 * <p>The whole card is the button. A small "Rent" target inside a large clickable-looking tile
 * is the classic catalogue mistake: people click the card, nothing happens, and they conclude
 * the listing is broken.
 */

/** Tint the hero tile by size so the grid is scannable by shape, not just by reading numbers. */
function tierClass(gpus: number): string {
  if (gpus > 8) return "from-brand-600 to-brand-800";
  if (gpus >= 5) return "from-brand-500 to-brand-700";
  return "from-brand-400 to-brand-600";
}

function HeroTile({ gpus, corner }: { gpus: number; corner?: React.ReactNode }) {
  return (
    <div
      className={cx(
        "relative flex h-28 items-center justify-center bg-gradient-to-br",
        tierClass(gpus)
      )}
    >
      <div className="text-center leading-none text-white">
        <span className="tnum text-4xl font-semibold tracking-tight">{gpus}</span>
        <span className="ml-1.5 text-sm font-medium text-white/80">
          GPU{gpus === 1 ? "" : "s"}
        </span>
      </div>
      {corner && <div className="absolute left-3 top-3">{corner}</div>}
    </div>
  );
}

/**
 * Badges on the hero tile.
 *
 * <p>These use fixed colours rather than theme tokens, deliberately. The tile behind them is a
 * brand gradient that looks the same in both themes, so a token that flips with the theme
 * would break the contrast on one of them — `text-positive` over white is a readable dark
 * green in light mode and an unreadable pale green in dark. A constant background deserves
 * constant foregrounds.
 */
function CardBadge({ tone, children }: { tone: "live" | "neutral"; children: React.ReactNode }) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium backdrop-blur-sm",
        tone === "live"
          ? "bg-white/95 text-[#0B8A5B]"
          : "bg-white/85 text-[#3F3F52]"
      )}
    >
      {tone === "live" && <span className="h-1.5 w-1.5 rounded-full bg-[#0B8A5B]" />}
      {children}
    </span>
  );
}

const shell =
  "group flex w-full flex-col overflow-hidden rounded-2xl border border-line bg-surface text-left shadow-card transition-all duration-200 hover:-translate-y-0.5 hover:border-line-strong hover:shadow-lift focus:outline-none focus-visible:ring-4 focus-visible:ring-brand-100";

export function SupplyCard({
  listing,
  onSelect,
  startsSoon,
}: {
  listing: SupplyListing;
  onSelect: () => void;
  startsSoon: boolean;
}) {
  return (
    <button type="button" onClick={onSelect} className={shell}>
      <HeroTile
        gpus={listing.availableGpus}
        corner={
          startsSoon ? (
            <CardBadge tone="live">Available now</CardBadge>
          ) : (
            <CardBadge tone="neutral">{listing.windowHours}h window</CardBadge>
          )
        }
      />

      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex items-baseline justify-between gap-2">
          <p className="tnum text-lg font-semibold tracking-tight text-ink">
            {money(listing.pricePerGpuHour)}
            <span className="ml-1 text-xs font-normal text-ink-muted">/ GPU-hr</span>
          </p>
          <span className="tnum shrink-0 text-sm text-ink-muted">{listing.windowHours}h</span>
        </div>

        <p className="text-sm leading-snug text-ink-soft">
          {fmtWindow(listing.windowStart, listing.windowEnd)}
        </p>

        <div className="mt-auto flex items-center justify-between border-t border-line pt-3">
          <span className="text-xs text-ink-muted">Full window / GPU</span>
          <span className="tnum text-sm font-medium text-ink">
            {money(listing.estimatedCostPerGpu)}
          </span>
        </div>
      </div>
    </button>
  );
}

export function DemandCard({
  request,
  onSelect,
  startsSoon,
}: {
  request: DemandListing;
  onSelect: () => void;
  startsSoon: boolean;
}) {
  return (
    <button type="button" onClick={onSelect} className={shell}>
      <HeroTile
        gpus={request.gpusWanted}
        corner={
          startsSoon ? (
            <CardBadge tone="live">Starting soon</CardBadge>
          ) : (
            <CardBadge tone="neutral">{request.windowHours}h window</CardBadge>
          )
        }
      />

      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex items-baseline justify-between gap-2">
          <p className="tnum text-lg font-semibold tracking-tight text-ink">
            {money(request.maxPricePerGpuHour)}
            <span className="ml-1 text-xs font-normal text-ink-muted">/ GPU-hr</span>
          </p>
          <span className="tnum shrink-0 text-sm text-ink-muted">{request.windowHours}h</span>
        </div>

        <p className="text-sm leading-snug text-ink-soft">
          {fmtWindow(request.windowStart, request.windowEnd)}
        </p>

        <div className="mt-auto flex items-center justify-between border-t border-line pt-3">
          <span className="text-xs text-ink-muted">You could earn</span>
          <span className="tnum text-sm font-medium text-positive">
            {money(request.maxRevenue)}
          </span>
        </div>
      </div>
    </button>
  );
}

/** Placeholder tiles while the first fetch is in flight — keeps the grid from popping in. */
export function CardSkeleton() {
  return (
    <div className="overflow-hidden rounded-2xl border border-line bg-surface shadow-card">
      <div className="h-28 animate-pulse bg-surface-sunken" />
      <div className="space-y-3 p-4">
        <div className="h-5 w-24 animate-pulse rounded bg-surface-sunken" />
        <div className="h-4 w-full animate-pulse rounded bg-surface-sunken" />
        <div className="h-4 w-2/3 animate-pulse rounded bg-surface-sunken" />
      </div>
    </div>
  );
}

/** The grid itself, so every catalogue breaks at the same widths. */
export function CardGrid({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {children}
    </div>
  );
}
