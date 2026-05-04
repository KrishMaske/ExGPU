export type OrderSide = "BUY" | "SELL";

export type OrderStatus =
  | "OPEN"
  | "PARTIALLY_FILLED"
  | "FILLED"
  | "EXPIRED"
  | "CANCELLED";

export type MatchStatus = "NO_MATCH" | "PARTIAL_FILL" | "FULL_FILL";

export type AllocationStatus = "ACTIVE" | "COMPLETED" | "KILLED" | "CANCELLED";

/** Window state relative to now, derived server-side in AllocationResponse. */
export type Lifecycle = "SCHEDULED" | "RUNNING" | "ENDED";

export interface WhoAmI {
  userId: string;
  email: string;
}

/**
 * One rentable listing on the public marketplace.
 *
 * Note the absence of an owner field — the backend deliberately does not expose which
 * provider is behind a listing.
 */
export interface SupplyListing {
  listingId: string;
  pricePerGpuHour: number;
  availableGpus: number;
  windowStart: string;
  windowEnd: string;
  windowHours: number;
  estimatedCostPerGpu: number;
}

export interface OrderResponse {
  id: string;
  ownerId: string;
  side: OrderSide;
  status: OrderStatus;
  pricePerGpuHour: number;
  quantity: number;
  filledQuantity: number;
  remainingQuantity: number;
  windowStart: string;
  windowEnd: string;
  priorityTimestamp: string;
  createdAt: string;
}

export interface AllocationResponse {
  id: string;
  buyOrderId: string;
  sellOrderId: string;
  quantity: number;
  windowStart: string;
  windowEnd: string;
  executionPrice: number | null;
  status: AllocationStatus;
  createdAt: string;
  cancelledAt?: string | null;
  refundedAmount?: number | null;
  lifecycle: Lifecycle;
  windowSeconds: number;
  /** Ceiling on what this rental can cost if the whole window is consumed. */
  maxCost: number | null;
}

export interface PlaceOrderResponse {
  order: OrderResponse;
  matchStatus: MatchStatus;
  totalMatchedQuantity: number;
  allocations: AllocationResponse[];
}

export interface CreateOrderRequest {
  side: OrderSide;
  pricePerGpuHour: number;
  quantity: number;
  startTime: string;
  endTime: string;
}

export interface BalanceResponse {
  ownerId: string;
  balance: number;
  /** -1 means "no balance row yet" — the user has never topped up. */
  version: number;
  updatedAt: string | null;
}

export interface CreateBalanceRequest {
  amount: number;
}

export interface SubmitUsageEventRequest {
  eventId: string;
  allocationId: string;
  usageSeconds: number;
}

export interface UsageEventResponse {
  ledgerId: string;
  allocationId: string;
  buyerId: string;
  usageSeconds: number;
  cost: number;
  remainingBalance: number;
  computeKilled: boolean;
  duplicate: boolean;
  createdAt: string;
}

export interface UsageLedgerEntry {
  id: string;
  allocationId: string;
  buyerId: string;
  usageSeconds: number;
  tokenCost: number;
  idempotencyKey: string;
  chargeType: ChargeType;
  createdAt: string;
}

/** One unfilled buy request, as shown to providers. No owner field — demand is anonymous. */
export interface DemandListing {
  requestId: string;
  maxPricePerGpuHour: number;
  gpusWanted: number;
  windowStart: string;
  windowEnd: string;
  windowHours: number;
  /** What filling the whole request at the buyer’s bid would earn. */
  maxRevenue: number;
}

export type RefundTierName = "FULL" | "PARTIAL" | "NONE";

/** Preview before cancelling, and receipt after — the same shape for both. */
export interface CancellationQuote {
  allocationId: string;
  alreadyCancelled: boolean;
  cancellable: boolean;
  tier: RefundTierName;
  refundRate: number;
  bookingCharge: number;
  refundAmount: number;
  noticeSeconds: number;
  windowStart: string;
  explanation: string;
}

export type ChargeType = "BOOKING" | "USAGE" | "REFUND";

export type AccessState = 'PENDING' | 'ACTIVE' | 'EXPIRED' | 'REVOKED';

export type RevokeReason = 'BALANCE_EXHAUSTED' | 'OPERATOR';

export interface ConnectionDetails {
  host: string;
  port: number;
  username: string;
  hint: string;
}

/**
 * Whether the buyer can get into a rental right now.
 *
 * One shape for all four states; fields not relevant to the current state are null.
 * accessKey is populated ONLY while state is ACTIVE.
 */
export interface AccessResponse {
  state: AccessState;
  allocationId: string;
  nodeRef: string;
  windowStart: string;
  windowEnd: string;
  /** Countdown while PENDING. */
  secondsUntilAvailable: number | null;
  /** Countdown to window end while ACTIVE. */
  secondsRemaining: number | null;
  accessKey: string | null;
  keyExpiresAt: string | null;
  connection: ConnectionDetails | null;
  revokeReason: RevokeReason | null;
  message: string;
}

export type RealtimeEventType =
  | "MARKET_UPDATED"
  | "ORDER_SUBMITTED"
  | "ORDER_FILLED"
  | "ALLOCATION_CREATED"
  | "USAGE_BILLED"
  | "BALANCE_UPDATED"
  | "DUPLICATE_USAGE_EVENT"
  | "COMPUTE_KILLED"
  | "ACCESS_GRANTED"
  | "ACCESS_REVOKED"
  | "DLQ_EVENT_CREATED";

export interface RealtimeEvent {
  id: string;
  type: RealtimeEventType;
  message: string;
  entityId: string | null;
  payload: unknown;
  createdAt: string;
}
