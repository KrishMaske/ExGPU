import type { RealtimeEventType } from "./types";

export interface EventMeta {
  label: string;
  /** Tailwind classes for a chip: border + background + text, tuned for a light surface. */
  badge: string;
  /** Tailwind class for the small status dot. */
  dot: string;
}

/**
 * Colour is used to signal what a viewer should do about an event, not to make the feed
 * colourful: brand purple for routine trading activity, green for money arriving or access
 * opening, red for anything that stops compute, neutral grey for informational noise.
 */
export const EVENT_META: Record<RealtimeEventType, EventMeta> = {
  MARKET_UPDATED: {
    label: "Market updated",
    badge: "border-line bg-surface-sunken text-ink-muted",
    dot: "bg-ink-faint",
  },
  ORDER_SUBMITTED: {
    label: "Order submitted",
    badge: "border-brand-200 bg-brand-50 text-brand-700",
    dot: "bg-brand-500",
  },
  ORDER_FILLED: {
    label: "Order filled",
    badge: "border-positive/25 bg-positive/[0.08] text-positive",
    dot: "bg-positive",
  },
  ALLOCATION_CREATED: {
    label: "Allocation created",
    badge: "border-brand-200 bg-brand-50 text-brand-700",
    dot: "bg-brand-600",
  },
  USAGE_BILLED: {
    label: "Usage billed",
    badge: "border-caution/25 bg-caution/[0.08] text-caution",
    dot: "bg-caution",
  },
  BALANCE_UPDATED: {
    label: "Balance updated",
    badge: "border-positive/25 bg-positive/[0.08] text-positive",
    dot: "bg-positive",
  },
  DUPLICATE_USAGE_EVENT: {
    label: "Duplicate skipped",
    badge: "border-line bg-surface-sunken text-ink-muted",
    dot: "bg-ink-faint",
  },
  COMPUTE_KILLED: {
    label: "Compute killed",
    badge: "border-negative/25 bg-negative/[0.06] text-negative",
    dot: "bg-negative",
  },
  ACCESS_GRANTED: {
    label: "Access granted",
    badge: "border-positive/25 bg-positive/[0.08] text-positive",
    dot: "bg-positive",
  },
  ACCESS_REVOKED: {
    label: "Access revoked",
    badge: "border-negative/25 bg-negative/[0.06] text-negative",
    dot: "bg-negative",
  },
  DLQ_EVENT_CREATED: {
    label: "DLQ event",
    badge: "border-negative/25 bg-negative/[0.06] text-negative",
    dot: "bg-negative",
  },
};

export function eventMeta(type: RealtimeEventType): EventMeta {
  return (
    EVENT_META[type] ?? {
      label: type,
      badge: "border-line bg-surface-sunken text-ink-muted",
      dot: "bg-ink-faint",
    }
  );
}
