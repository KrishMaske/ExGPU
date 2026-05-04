import type {
  AccessResponse,
  AllocationResponse,
  CancellationQuote,
  DemandListing,
  BalanceResponse,
  CreateBalanceRequest,
  CreateOrderRequest,
  OrderResponse,
  PlaceOrderResponse,
  SubmitUsageEventRequest,
  SupplyListing,
  UsageEventResponse,
  UsageLedgerEntry,
  WhoAmI,
} from "./types";

const DEV_API_BASE = "http://localhost:8080";

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ??
  (process.env.NODE_ENV === "development" ? DEV_API_BASE : "");

function configuredApiBase(): string {
  if (!API_BASE) {
    throw new Error(
      "ExGPU API is not configured. Set NEXT_PUBLIC_API_BASE and rebuild the frontend."
    );
  }
  return API_BASE;
}

export const LINKS = {
  swagger: API_BASE ? `${API_BASE}/swagger-ui.html` : "",
  actuatorPrometheus: API_BASE ? `${API_BASE}/actuator/prometheus` : "",
  prometheus: "http://localhost:9090",
  grafana: "http://localhost:3000",
};

/**
 * Supplies the current Supabase access token.
 *
 * Installed by AuthProvider. It is a getter rather than a value so that every request reads
 * the token at call time — Supabase rotates access tokens periodically, and a captured
 * string would go stale.
 */
type TokenGetter = () => string | null;
let getAccessToken: TokenGetter = () => null;

export function setAccessTokenGetter(getter: TokenGetter): void {
  getAccessToken = getter;
}

/** Thrown for non-2xx responses, carrying the HTTP status so callers can branch on 401. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface HttpOptions extends RequestInit {
  /** Skip the Authorization header. Used for the public marketplace endpoints. */
  anonymous?: boolean;
}

async function http<T>(path: string, options: HttpOptions = {}): Promise<T> {
  const { anonymous, ...init } = options;

  // Only declare a content type when there is actually a body. A GET carrying
  // Content-Type is not a CORS "simple request" and forces a preflight on every read.
  const headers: Record<string, string> = {};
  if (init.body) headers["Content-Type"] = "application/json";

  if (!anonymous) {
    const token = getAccessToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${configuredApiBase()}${path}`, {
    cache: "no-store",
    ...init,
    headers: { ...headers, ...(init.headers as Record<string, string> | undefined) },
  });

  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body.detail || body.message || body.error || JSON.stringify(body);
    } catch {
      /* non-JSON error body */
    }
    if (res.status === 401) detail = "Your session has expired. Please sign in again.";
    throw new ApiError(res.status, detail);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  /** Public — no account needed. The landing page and browse page both read this. */
  marketSupply: () => http<SupplyListing[]>("/market/supply", { anonymous: true }),

  /** Open buy requests a provider could fill. Requires auth, unlike supply. */
  marketDemand: () => http<DemandListing[]>("/market/demand"),

  health: () => http<{ status: string }>("/actuator/health", { anonymous: true }),

  // ── The signed-in user ────────────────────────────────────────────────────
  whoAmI: () => http<WhoAmI>("/me"),
  myBalance: () => http<BalanceResponse>("/me/balance"),
  myRentals: () => http<AllocationResponse[]>("/me/rentals"),
  mySupply: () => http<AllocationResponse[]>("/me/supply"),
  myUsage: () => http<UsageLedgerEntry[]>("/me/usage"),

  /**
   * Access state for one rental. Safe to poll: the backend derives state from the clock
   * and mints a credential that is byte-identical for every call within a 15-minute
   * bucket, so repeated polls neither churn secrets nor accumulate state.
   */
  rentalAccess: (allocationId: string) =>
    http<AccessResponse>(`/me/rentals/${allocationId}/access`),

  /** What cancelling would refund, without cancelling. */
  cancellationQuote: (allocationId: string) =>
    http<CancellationQuote>(`/me/rentals/${allocationId}/cancellation-quote`),

  cancelRental: (allocationId: string) =>
    http<CancellationQuote>(`/me/rentals/${allocationId}/cancel`, { method: "POST" }),

  /** Fill an open buy request with your GPUs. Price and window come from the request. */
  fillDemand: (buyOrderId: string, gpus: number) =>
    http<PlaceOrderResponse>(`/orders/demand/${buyOrderId}/fill`, {
      method: "POST",
      body: JSON.stringify({ gpus }),
    }),

  myOrders: (side?: "BUY" | "SELL") =>
    http<OrderResponse[]>(`/orders/me${side ? `?side=${side}` : ""}`),

  // ── Actions ───────────────────────────────────────────────────────────────
  placeOrder: (body: CreateOrderRequest) =>
    http<PlaceOrderResponse>("/orders", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  topUp: (body: CreateBalanceRequest) =>
    http<BalanceResponse>("/balances", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  submitUsageEvent: (body: SubmitUsageEventRequest) =>
    http<UsageEventResponse>("/usage-events", {
      method: "POST",
      body: JSON.stringify(body),
    }),
};
