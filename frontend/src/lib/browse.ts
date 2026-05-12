import type { DemandListing, SupplyListing } from "./types";

/**
 * Filtering and sorting for the browse grids.
 *
 * <p>Kept out of the components because both sides of the market browse the same way — buyers
 * scan supply, providers scan demand — and the two grids should not drift apart in how "soon",
 * "short" or "cheap" are defined.
 *
 * <p>All of it runs client-side over the full list the API already returned. That is honest at
 * this size (a marketplace with tens of open listings) and keeps filtering instant; if the book
 * ever grows past a few hundred live listings this belongs in the query instead.
 */

const HOUR_MS = 3_600_000;

/** A chip is a named predicate — the quick, one-tap cuts across the catalogue. */
export interface Chip<T> {
  id: string;
  label: string;
  match: (item: T, now: number) => boolean;
}

export type SortKey = "price" | "gpus" | "soonest" | "duration";

export interface BrowseFilters {
  chip: string;
  /** Ceiling on price per GPU-hour. Null means no ceiling. */
  maxPrice: number | null;
  /** Only listings offering at least this many GPUs. Null means any. */
  minGpus: number | null;
  sort: SortKey;
}

export const DEFAULT_FILTERS: BrowseFilters = {
  chip: "all",
  maxPrice: null,
  minGpus: null,
  sort: "price",
};

export function filtersAreDefault(f: BrowseFilters): boolean {
  return (
    f.chip === DEFAULT_FILTERS.chip &&
    f.maxPrice === DEFAULT_FILTERS.maxPrice &&
    f.minGpus === DEFAULT_FILTERS.minGpus &&
    f.sort === DEFAULT_FILTERS.sort
  );
}

/** How many non-chip filters are narrowing the grid — drives the "N" badge on the bar. */
export function activeFilterCount(f: BrowseFilters): number {
  return (f.maxPrice !== null ? 1 : 0) + (f.minGpus !== null ? 1 : 0);
}

/**
 * Buyer-side chips.
 *
 * <p>There is no GPU model in the listing payload — the backend deliberately exposes only
 * quantity, price and window — so these cut by the three dimensions that do exist rather than
 * inventing hardware categories the API cannot back.
 */
export const SUPPLY_CHIPS: Chip<SupplyListing>[] = [
  { id: "all", label: "All", match: () => true },
  {
    id: "now",
    label: "Available now",
    // Already inside its window, or opening within the hour — "I need compute today".
    match: (l, now) => Date.parse(l.windowStart) <= now + HOUR_MS,
  },
  {
    id: "today",
    label: "Today",
    match: (l, now) => new Date(l.windowStart).toDateString() === new Date(now).toDateString(),
  },
  { id: "under2", label: "Under $2/hr", match: (l) => l.pricePerGpuHour < 2 },
  { id: "small", label: "1–4 GPUs", match: (l) => l.availableGpus <= 4 },
  { id: "medium", label: "5–8 GPUs", match: (l) => l.availableGpus >= 5 && l.availableGpus <= 8 },
  { id: "large", label: "8+ GPUs", match: (l) => l.availableGpus > 8 },
  { id: "short", label: "Short (≤2h)", match: (l) => l.windowHours <= 2 },
  { id: "long", label: "Long (4h+)", match: (l) => l.windowHours >= 4 },
];

/** Provider-side chips: the same cuts, read from the demand side of the book. */
export const DEMAND_CHIPS: Chip<DemandListing>[] = [
  { id: "all", label: "All", match: () => true },
  {
    id: "now",
    label: "Starting soon",
    match: (d, now) => Date.parse(d.windowStart) <= now + HOUR_MS,
  },
  {
    id: "today",
    label: "Today",
    match: (d, now) => new Date(d.windowStart).toDateString() === new Date(now).toDateString(),
  },
  { id: "pays2", label: "Pays $2+/hr", match: (d) => d.maxPricePerGpuHour >= 2 },
  { id: "small", label: "1–4 GPUs", match: (d) => d.gpusWanted <= 4 },
  { id: "medium", label: "5–8 GPUs", match: (d) => d.gpusWanted >= 5 && d.gpusWanted <= 8 },
  { id: "large", label: "8+ GPUs", match: (d) => d.gpusWanted > 8 },
  { id: "short", label: "Short (≤2h)", match: (d) => d.windowHours <= 2 },
  { id: "long", label: "Long (4h+)", match: (d) => d.windowHours >= 4 },
];

/** Shape-agnostic view of a listing, so one filter routine serves both sides of the book. */
interface Browsable {
  price: number;
  gpus: number;
  startMs: number;
  hours: number;
}

const supplyView = (l: SupplyListing): Browsable => ({
  price: l.pricePerGpuHour,
  gpus: l.availableGpus,
  startMs: Date.parse(l.windowStart),
  hours: l.windowHours,
});

const demandView = (d: DemandListing): Browsable => ({
  price: d.maxPricePerGpuHour,
  gpus: d.gpusWanted,
  startMs: Date.parse(d.windowStart),
  hours: d.windowHours,
});

function apply<T>(
  items: T[],
  view: (item: T) => Browsable,
  chips: Chip<T>[],
  filters: BrowseFilters,
  now: number,
  /** Providers sort by best-paying first; buyers by cheapest first. */
  priceAscending: boolean
): T[] {
  const chip = chips.find((c) => c.id === filters.chip) ?? chips[0];

  const kept = items.filter((item) => {
    if (!chip.match(item, now)) return false;
    const v = view(item);
    if (filters.maxPrice !== null && v.price > filters.maxPrice) return false;
    if (filters.minGpus !== null && v.gpus < filters.minGpus) return false;
    return true;
  });

  const sorted = [...kept];
  sorted.sort((a, b) => {
    const x = view(a);
    const y = view(b);
    switch (filters.sort) {
      case "price":
        return priceAscending ? x.price - y.price : y.price - x.price;
      case "gpus":
        return y.gpus - x.gpus;
      case "soonest":
        return x.startMs - y.startMs;
      case "duration":
        return y.hours - x.hours;
    }
  });
  return sorted;
}

export function filterSupply(
  listings: SupplyListing[],
  filters: BrowseFilters,
  now = Date.now()
): SupplyListing[] {
  return apply(listings, supplyView, SUPPLY_CHIPS, filters, now, true);
}

export function filterDemand(
  requests: DemandListing[],
  filters: BrowseFilters,
  now = Date.now()
): DemandListing[] {
  // Providers care about the best-paying request, so price sorts descending here.
  return apply(requests, demandView, DEMAND_CHIPS, filters, now, false);
}

/** Label for the active sort, shown on the sort pill. */
export const SORT_LABELS: Record<SortKey, string> = {
  price: "Price",
  gpus: "Most GPUs",
  soonest: "Starts soonest",
  duration: "Longest window",
};
