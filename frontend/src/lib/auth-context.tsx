"use client";

import type { Session, User } from "@supabase/supabase-js";
import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { supabase, isSupabaseConfigured } from "./supabase";
import { setAccessTokenGetter } from "./api";

interface AuthContextValue {
  user: User | null;
  session: Session | null;
  /** True until the initial session lookup finishes — guards against a redirect flash. */
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string) => Promise<{ needsConfirmation: boolean }>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Turns Supabase's terser failures into something actionable.
 *
 * "Failed to fetch" in particular is a bare TypeError from the browser's fetch — it means
 * the request never reached Supabase at all. The usual causes are a stale bundle still
 * pointing at the placeholder URL (fixed by a hard refresh) or an extension blocking the
 * domain, neither of which is guessable from the raw message.
 */
function explain(message: string): string {
  if (/failed to fetch|networkerror|load failed/i.test(message)) {
    return "Could not reach the authentication server. Hard-refresh the page (Ctrl+Shift+R); if it persists, check that no browser extension is blocking supabase.co.";
  }
  if (/email rate limit|over_email_send_rate_limit/i.test(message)) {
    return "Supabase's signup email limit was hit (about 3/hour on the free tier). Turn off 'Confirm email' in the Supabase dashboard, or wait an hour.";
  }
  if (/email address .* is invalid/i.test(message)) {
    return "Supabase rejected that email domain. Use a real, deliverable address.";
  }
  return message;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}

/**
 * Owns the Supabase session and makes the access token available to the API layer.
 *
 * <p>The token is handed to `api.ts` through {@link setAccessTokenGetter} rather than being
 * passed into every call. That keeps the API module free of React imports while still
 * guaranteeing that requests carry whatever token is current at the moment they fire —
 * important because Supabase rotates the access token roughly hourly, and a token captured
 * once at mount would start failing after a refresh.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    if (!isSupabaseConfigured) {
      setLoading(false);
      return;
    }

    let active = true;

    supabase.auth.getSession().then(({ data }) => {
      if (!active) return;
      setSession(data.session);
      setLoading(false);
    });

    // Fires on sign-in, sign-out, and every silent token refresh.
    const { data: sub } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next);
    });

    return () => {
      active = false;
      sub.subscription.unsubscribe();
    };
  }, []);

  // Read the token lazily at call time so refreshes are picked up automatically.
  useEffect(() => {
    setAccessTokenGetter(() => session?.access_token ?? null);
  }, [session]);

  const signIn = useCallback(async (email: string, password: string) => {
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    if (error) throw new Error(explain(error.message));
  }, []);

  const signUp = useCallback(async (email: string, password: string) => {
    const { data, error } = await supabase.auth.signUp({ email, password });
    if (error) throw new Error(explain(error.message));
    // With email confirmation enabled, Supabase returns a user but no session. Surfacing
    // that distinction lets the UI say "check your inbox" instead of silently doing nothing.
    return { needsConfirmation: !data.session };
  }, []);

  const signOut = useCallback(async () => {
    await supabase.auth.signOut();
    setSession(null);
    router.push("/");
  }, [router]);

  return (
    <AuthContext.Provider
      value={{
        user: session?.user ?? null,
        session,
        loading,
        signIn,
        signUp,
        signOut,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
