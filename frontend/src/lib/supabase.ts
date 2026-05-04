import { createBrowserClient } from "@supabase/ssr";

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL;
const SUPABASE_ANON_KEY = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

/**
 * Whether Supabase credentials are configured at all.
 *
 * Checked rather than assumed so a missing .env.local produces a readable "auth is not
 * configured" screen instead of an opaque crash inside the Supabase SDK.
 */
export const isSupabaseConfigured = Boolean(SUPABASE_URL && SUPABASE_ANON_KEY);

/**
 * Browser-side Supabase client, used only for authentication — sign-up, sign-in, sign-out,
 * and refreshing the session.
 *
 * <p>No application data is read through it. Orders, allocations and balances all live in
 * the Spring backend's Postgres, and this client's only job is to obtain an access token to
 * present there. That keeps one source of truth for exchange data and means Supabase never
 * needs table-level rules for domain objects.
 *
 * <p>createBrowserClient persists the session in cookies (not localStorage), which is what
 * lets the Next.js middleware see whether a visitor is signed in before rendering a
 * protected route.
 */
// `||`, not `??`. An env var set to an empty string — which is what happens when a shell
// exports it blank, or a .env line has no value — is nullish-coalescing's blind spot: `??`
// passes "" straight through, and createBrowserClient then throws "Your project's URL and API
// key are required" at module evaluation. That throw happens during import of the auth
// context, so it takes down every route that touches it rather than degrading to the
// "auth is not configured" screen this fallback exists to reach.
export const supabase = createBrowserClient(
  SUPABASE_URL || "https://placeholder.supabase.co",
  SUPABASE_ANON_KEY || "placeholder-key"
);
