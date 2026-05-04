import type { SupplyListing } from "./types";

export interface RentalDraft {
  quantity: number;
  maxPrice: number;
  start: string;
  end: string;
  listingId?: string;
}

export type RentalField = "quantity" | "maxPrice" | "start" | "end";
export type RentalErrors = Partial<Record<RentalField, string>>;

export function validateRentalDraft(input: { quantity: string; maxPrice: string; start: string; end: string }): { draft: RentalDraft | null; errors: RentalErrors } {
  const errors: RentalErrors = {};
  const quantity = Number(input.quantity);
  const maxPrice = Number(input.maxPrice);
  const startMs = Date.parse(input.start);
  const endMs = Date.parse(input.end);

  if (!input.quantity) errors.quantity = "Enter the number of GPUs you need.";
  else if (!Number.isInteger(quantity) || quantity < 1) errors.quantity = "GPU quantity must be a whole number of at least 1.";
  else if (quantity > 1_000_000) errors.quantity = "GPU quantity is too large.";

  if (!input.maxPrice) errors.maxPrice = "Enter your maximum price.";
  else if (!Number.isFinite(maxPrice) || maxPrice <= 0) errors.maxPrice = "Maximum price must be greater than zero.";
  else if (maxPrice > 1_000_000) errors.maxPrice = "Maximum price is too large.";

  if (!input.start || !Number.isFinite(startMs)) errors.start = "Choose a valid start date and time.";
  if (!input.end || !Number.isFinite(endMs)) errors.end = "Choose a valid end date and time.";
  else if (Number.isFinite(startMs) && endMs <= startMs) errors.end = "End time must be later than start time.";

  if (Object.keys(errors).length) return { draft: null, errors };
  return { draft: { quantity, maxPrice, start: new Date(startMs).toISOString(), end: new Date(endMs).toISOString() }, errors };
}

export function listingMatches(listing: SupplyListing, draft: RentalDraft): boolean {
  return listing.availableGpus >= draft.quantity && listing.pricePerGpuHour <= draft.maxPrice && Date.parse(listing.windowStart) <= Date.parse(draft.start) && Date.parse(listing.windowEnd) >= Date.parse(draft.end);
}

export function rentalDraftForListing(listing: SupplyListing, requested?: RentalDraft | null): RentalDraft {
  const compatible = requested ? listingMatches(listing, requested) : false;
  return {
    quantity: Math.min(listing.availableGpus, compatible && requested ? requested.quantity : 1),
    maxPrice: compatible && requested ? requested.maxPrice : listing.pricePerGpuHour,
    start: compatible && requested ? requested.start : listing.windowStart,
    end: compatible && requested ? requested.end : listing.windowEnd,
    listingId: listing.listingId,
  };
}

export function rentHref(draft: RentalDraft): string {
  const params = new URLSearchParams({ quantity: String(draft.quantity), maxPrice: String(draft.maxPrice), start: draft.start, end: draft.end });
  if (draft.listingId) params.set("listing", draft.listingId);
  return `/app/rent?${params.toString()}`;
}

export function parseRentalDraft(params: Pick<URLSearchParams, "get">): RentalDraft | null {
  const quantity = params.get("quantity");
  const maxPrice = params.get("maxPrice");
  const start = params.get("start");
  const end = params.get("end");
  if (!quantity || !maxPrice || !start || !end) return null;
  const checked = validateRentalDraft({ quantity, maxPrice, start, end });
  if (!checked.draft) return null;
  const listing = params.get("listing");
  return { ...checked.draft, listingId: listing && /^[A-Za-z0-9_-]{1,128}$/.test(listing) ? listing : undefined };
}

export function toLocalInput(iso: string): string {
  const date = new Date(iso);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}
