"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useAuth } from "@/lib/auth-context";
import { isSupabaseConfigured } from "@/lib/supabase";
import { authHref, safeAppPath } from "@/lib/navigation";
import { Logo } from "./Logo";

type Mode = "signin" | "signup";

/** Shared sign-in / sign-up form — same fields and error surface, different copy and call. */
export function AuthForm({ mode }: { mode: Mode }) {
  const isSignUp = mode === "signup";
  const { signIn, signUp } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const nextPath = safeAppPath(params.get("next"));

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmSent, setConfirmSent] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (isSignUp) {
        const { needsConfirmation } = await signUp(email, password);
        if (needsConfirmation) {
          setConfirmSent(true);
          return;
        }
      } else {
        await signIn(email, password);
      }
      // Honour ?next= so a deep link survives the sign-in detour.
      router.push(nextPath);
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setBusy(false);
    }
  }

  if (!isSupabaseConfigured) {
    return (
      <Shell title="Authentication isn't configured">
        <p className="text-sm leading-relaxed text-ink-soft">
          <Code>NEXT_PUBLIC_SUPABASE_URL</Code> and <Code>NEXT_PUBLIC_SUPABASE_ANON_KEY</Code>{" "}
          are missing from <Code>frontend/.env.local</Code>. Add them and restart the dev
          server.
        </p>
      </Shell>
    );
  }

  if (confirmSent) {
    return (
      <Shell title="Check your email">
        <p className="text-sm leading-relaxed text-ink-soft">
          We sent a confirmation link to <span className="font-medium text-ink">{email}</span>.
          Click it to activate your account, then sign in.
        </p>
        <Link
          href={authHref("/login", nextPath)}
          className="mt-7 block w-full rounded-full border border-line-strong py-3 text-center text-sm font-medium text-ink transition hover:bg-surface-sunken"
        >
          Back to sign in
        </Link>
      </Shell>
    );
  }

  return (
    <Shell
      title={isSignUp ? "Create your account" : "Welcome back"}
      subtitle={
        isSignUp
          ? "Rent GPU compute, or list your own."
          : "Sign in to your ExGPU account."
      }
    >
      <form onSubmit={onSubmit} className="space-y-5">
        <label className="block">
          <span className="mb-1.5 block text-sm font-medium text-ink-soft">Email</span>
          <input
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            className={inputCls}
          />
        </label>

        <label className="block">
          <span className="mb-1.5 block text-sm font-medium text-ink-soft">Password</span>
          <input
            type="password"
            required
            minLength={6}
            autoComplete={isSignUp ? "new-password" : "current-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={isSignUp ? "At least 6 characters" : "••••••••"}
            className={inputCls}
          />
        </label>

        {error && (
          <p className="rounded-xl border border-negative/25 bg-negative/[0.06] px-4 py-3 text-sm leading-relaxed text-negative">
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-full bg-brand-600 py-3 text-sm font-medium text-white shadow-brand transition hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none"
        >
          {busy
            ? isSignUp
              ? "Creating account…"
              : "Signing in…"
            : isSignUp
              ? "Create account"
              : "Sign in"}
        </button>
      </form>

      <p className="mt-7 text-center text-sm text-ink-muted">
        {isSignUp ? "Already have an account? " : "New to ExGPU? "}
        <Link
          href={authHref(isSignUp ? "/login" : "/signup", nextPath)}
          className="font-medium text-brand-600 transition hover:text-brand-700"
        >
          {isSignUp ? "Sign in" : "Create one"}
        </Link>
      </p>
    </Shell>
  );
}

const inputCls =
  "w-full rounded-xl border border-line-strong bg-surface px-4 py-3 text-sm text-ink placeholder:text-ink-faint transition focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-100";

function Code({ children }: { children: React.ReactNode }) {
  return (
    <code className="rounded bg-surface-sunken px-1.5 py-0.5 font-mono text-xs text-ink">
      {children}
    </code>
  );
}

function Shell({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-surface-muted px-6 py-12">
      <Link href="/" className="mb-8">
        <Logo />
      </Link>

      <div className="w-full max-w-md rounded-2xl border border-line bg-surface p-8 shadow-lift">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">{title}</h1>
        {subtitle && <p className="mt-1.5 text-sm text-ink-muted">{subtitle}</p>}
        <div className="mt-7">{children}</div>
      </div>

      <Link
        href="/"
        className="mt-6 text-sm text-ink-muted transition hover:text-ink"
      >
        ← Back to home
      </Link>
    </div>
  );
}
