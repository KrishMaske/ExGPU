"use client";

import { useEffect, useId, useRef, useState } from "react";
import { cx } from "./ui";
import {
  activeFilterCount,
  DEFAULT_FILTERS,
  SORT_LABELS,
  type BrowseFilters,
  type Chip,
  type SortKey,
} from "@/lib/browse";

/**
 * The browse chrome that sits above every catalogue grid: a scrolling row of category chips,
 * then a sticky bar of filter pills.
 *
 * <p>The split is deliberate. Chips are the one-tap cuts people actually reach for and are
 * mutually exclusive, so they read as "which shelf am I looking at". The pills below are
 * precise, independent, and compose with whichever chip is active. Collapsing both into one
 * row would make the common case as fiddly as the rare one.
 */

export function Chips<T>({
  chips,
  active,
  onSelect,
  counts,
}: {
  chips: Chip<T>[];
  active: string;
  onSelect: (id: string) => void;
  /** Result count per chip, so a chip that leads nowhere can be shown as empty. */
  counts?: Record<string, number>;
}) {
  return (
    <div className="relative -mx-4 sm:mx-0">
      {/* Edge fades hint that the row keeps going, without a scrollbar sitting in the layout. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-y-0 right-0 z-10 w-10 bg-gradient-to-l from-surface-muted to-transparent sm:hidden"
      />
      <div
        role="tablist"
        aria-label="Categories"
        className="flex snap-x gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden sm:px-0"
      >
        {chips.map((chip) => {
          const isActive = chip.id === active;
          const count = counts?.[chip.id];
          const empty = count === 0 && chip.id !== "all";
          return (
            <button
              key={chip.id}
              role="tab"
              aria-selected={isActive}
              onClick={() => onSelect(chip.id)}
              className={cx(
                "min-h-10 shrink-0 snap-start whitespace-nowrap rounded-full border px-4 text-sm font-medium transition-all duration-150",
                isActive
                  ? "border-transparent bg-ink text-white shadow-card"
                  : empty
                    ? "border-line bg-surface text-ink-faint hover:border-line-strong"
                    : "border-line bg-surface text-ink-soft hover:border-ink-faint hover:text-ink"
              )}
            >
              {chip.label}
              {count !== undefined && (
                <span className={cx("tnum ml-1.5 text-xs", isActive ? "text-white/70" : "text-ink-faint")}>
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export function FilterBar({
  filters,
  onChange,
  resultCount,
  priceLabel = "Max price",
  sortPriceLabel = "Price: low to high",
}: {
  filters: BrowseFilters;
  onChange: (next: BrowseFilters) => void;
  resultCount: number;
  /** Buyers cap the price they pay; providers filter on what a request pays. */
  priceLabel?: string;
  sortPriceLabel?: string;
}) {
  const extras = activeFilterCount(filters);
  const dirty = extras > 0 || filters.sort !== DEFAULT_FILTERS.sort;

  const sortLabels: Record<SortKey, string> = { ...SORT_LABELS, price: sortPriceLabel };

  return (
    // Docks flush under the app header, whose real height AppShell publishes as
    // --app-header-h. The fallback only matters for the first paint before that runs.
    <div
      className="sticky z-30 -mx-4 border-b border-line bg-surface-muted/90 px-4 py-3 backdrop-blur-md sm:mx-0 sm:rounded-2xl sm:border sm:px-4"
      style={{ top: "var(--app-header-h, 7rem)" }}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Popover
          label={filters.maxPrice === null ? priceLabel : `≤ $${filters.maxPrice}/hr`}
          active={filters.maxPrice !== null}
          onClear={filters.maxPrice === null ? undefined : () => onChange({ ...filters, maxPrice: null })}
        >
          {(close) => (
            <PriceOptions
              value={filters.maxPrice}
              onPick={(maxPrice) => {
                onChange({ ...filters, maxPrice });
                close();
              }}
            />
          )}
        </Popover>

        <Popover
          label={filters.minGpus === null ? "GPUs" : `${filters.minGpus}+ GPUs`}
          active={filters.minGpus !== null}
          onClear={filters.minGpus === null ? undefined : () => onChange({ ...filters, minGpus: null })}
        >
          {(close) => (
            <GpuOptions
              value={filters.minGpus}
              onPick={(minGpus) => {
                onChange({ ...filters, minGpus });
                close();
              }}
            />
          )}
        </Popover>

        <Popover label={`Sort: ${sortLabels[filters.sort]}`} active={filters.sort !== DEFAULT_FILTERS.sort}>
          {(close) => (
            <ul className="py-1">
              {(Object.keys(sortLabels) as SortKey[]).map((key) => (
                <li key={key}>
                  <button
                    onClick={() => {
                      onChange({ ...filters, sort: key });
                      close();
                    }}
                    className={cx(
                      "flex w-full items-center justify-between gap-3 px-3.5 py-2.5 text-left text-sm transition hover:bg-surface-sunken",
                      filters.sort === key ? "font-medium text-brand-700" : "text-ink-soft"
                    )}
                  >
                    {sortLabels[key]}
                    {filters.sort === key && <Tick />}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Popover>

        {dirty && (
          <button
            onClick={() => onChange({ ...filters, ...DEFAULT_FILTERS, chip: filters.chip })}
            className="min-h-10 rounded-full px-3 text-sm font-medium text-ink-muted transition hover:text-ink"
          >
            Clear
          </button>
        )}

        <span aria-live="polite" className="tnum ml-auto pr-1 text-sm text-ink-muted">
          {resultCount} {resultCount === 1 ? "listing" : "listings"}
        </span>
      </div>
    </div>
  );
}

/** A filter pill that opens a small panel. Closes on outside click, Escape, or selection. */
function Popover({
  label,
  active,
  onClear,
  children,
}: {
  label: string;
  active: boolean;
  onClear?: () => void;
  children: (close: () => void) => React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <span
        className={cx(
          "inline-flex items-center rounded-full border transition-colors",
          active
            ? "border-brand-300 bg-brand-50 text-brand-700"
            : "border-line-strong bg-surface text-ink-soft hover:border-ink-faint"
        )}
      >
        <button
          ref={triggerRef}
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          className="inline-flex min-h-10 items-center gap-1.5 rounded-full px-3.5 text-sm font-medium"
        >
          {label}
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" aria-hidden className={cx("transition-transform", open && "rotate-180")}>
            <path d="M2 3.5L5 6.5L8 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        {onClear && (
          <button
            onClick={onClear}
            aria-label={`Clear ${label}`}
            className="-ml-1 mr-1.5 flex h-5 w-5 items-center justify-center rounded-full text-brand-700/70 transition hover:bg-brand-100 hover:text-brand-700"
          >
            <svg width="9" height="9" viewBox="0 0 10 10" fill="none" aria-hidden>
              <path d="M2.5 2.5l5 5M7.5 2.5l-5 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
        )}
      </span>

      {open && (
        <div
          id={panelId}
          className="absolute left-0 top-12 z-40 w-56 animate-slide-in overflow-hidden rounded-xl border border-line bg-surface shadow-lift"
        >
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  );
}

function PriceOptions({ value, onPick }: { value: number | null; onPick: (v: number | null) => void }) {
  const options: Array<{ label: string; value: number | null }> = [
    { label: "Any price", value: null },
    { label: "Under $1.00/hr", value: 1 },
    { label: "Under $2.00/hr", value: 2 },
    { label: "Under $3.00/hr", value: 3 },
    { label: "Under $5.00/hr", value: 5 },
  ];
  return <OptionList options={options} value={value} onPick={onPick} />;
}

function GpuOptions({ value, onPick }: { value: number | null; onPick: (v: number | null) => void }) {
  const options: Array<{ label: string; value: number | null }> = [
    { label: "Any number", value: null },
    { label: "2 or more", value: 2 },
    { label: "4 or more", value: 4 },
    { label: "8 or more", value: 8 },
    { label: "16 or more", value: 16 },
  ];
  return <OptionList options={options} value={value} onPick={onPick} />;
}

function OptionList({
  options,
  value,
  onPick,
}: {
  options: Array<{ label: string; value: number | null }>;
  value: number | null;
  onPick: (v: number | null) => void;
}) {
  return (
    <ul className="py-1">
      {options.map((o) => (
        <li key={o.label}>
          <button
            onClick={() => onPick(o.value)}
            className={cx(
              "flex w-full items-center justify-between gap-3 px-3.5 py-2.5 text-left text-sm transition hover:bg-surface-sunken",
              o.value === value ? "font-medium text-brand-700" : "text-ink-soft"
            )}
          >
            {o.label}
            {o.value === value && <Tick />}
          </button>
        </li>
      ))}
    </ul>
  );
}

function Tick() {
  return (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" aria-hidden>
      <path d="M2.5 7.5l3 3 6-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
