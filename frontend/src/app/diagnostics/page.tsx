"use client";

import { useCallback, useEffect, useState } from "react";
import { supabase } from "@/lib/supabase";

/**
 * Browser-side connectivity diagnostics.
 *
 * <p>Exists because "Failed to fetch" is a bare TypeError that tells you nothing: it is the
 * same message whether DNS failed, an extension blocked the domain, TLS was intercepted, or
 * the host is down. Curl from the same machine cannot reproduce any of those, because the
 * cause is specific to the browser's network stack.
 *
 * <p>Each check below runs from the page itself and reports the real outcome, which narrows
 * the cause in one pass. Safe to leave in the app — it reads config that is already public
 * in the bundle and never displays the key itself.
 */
type Result = {
  name: string;
  status: "pending" | "pass" | "fail" | "warn";
  detail: string;
  hint?: string;
};

export default function DiagnosticsPage() {
  const [results, setResults] = useState<Result[]>([]);
  const [running, setRunning] = useState(false);

  const run = useCallback(async () => {
    setRunning(true);
    const out: Result[] = [];
    const push = (r: Result) => {
      out.push(r);
      setResults([...out]);
    };

    const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
    const apiBase = process.env.NEXT_PUBLIC_API_BASE;

    // 1 — is the config even present in this bundle?
    push({
      name: "1. Supabase config present in bundle",
      status: url && key ? "pass" : "fail",
      detail: url
        ? `${url} · key ${key ? key.slice(0, 18) + "…" : "MISSING"}`
        : "NEXT_PUBLIC_SUPABASE_URL is undefined",
      hint: url
        ? undefined
        : "frontend/.env.local is missing or the dev server was started before it existed. Restart `npm run dev`.",
    });

    if (!url || !key) {
      setRunning(false);
      return;
    }

    // 2 — can the browser reach Supabase at all? No apikey, so 401 is a SUCCESS here:
    // it proves a real HTTP response came back rather than the request being blocked.
    try {
      const res = await fetch(`${url}/auth/v1/health`, { cache: "no-store" });
      push({
        name: "2. Browser can reach Supabase",
        status: "pass",
        detail: `HTTP ${res.status} — the request completed (any status means the network path works)`,
      });
    } catch (e) {
      push({
        name: "2. Browser can reach Supabase",
        status: "fail",
        detail: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
        hint:
          "The request never left the browser. Almost always an extension (uBlock/Privacy Badger/Brave Shields/Ghostery) blocking supabase.co, or antivirus TLS interception. Try a Private/Incognito window with extensions disabled.",
      });
    }

    // 2a — CSP is the usual cause of a "Failed to fetch" that curl cannot reproduce, because
    // the browser blocks the request before it reaches the network. Read the policy the page
    // was actually served with and confirm Supabase is listed in connect-src.
    //
    // This check exists because omitting an origin from connect-src fails *silently*: the
    // symptom is a bare TypeError identical to the host being down, which sends you hunting
    // for network problems that do not exist.
    try {
      const res = await fetch(window.location.href, { cache: "no-store" });
      const policy = res.headers.get("content-security-policy") ?? "";
      const connectSrc =
        policy
          .split(";")
          .map((d) => d.trim())
          .find((d) => d.startsWith("connect-src")) ?? "";
      const supabaseHost = new URL(url).host;
      const listed = connectSrc.includes(supabaseHost);

      push({
        name: "2a. CSP allows Supabase (connect-src)",
        status: listed ? "pass" : "fail",
        detail: connectSrc || "no connect-src directive found",
        hint: listed
          ? undefined
          : "Supabase is missing from connect-src in next.config.mjs, so the browser blocks every auth request before it leaves the page. Add the origin there and restart the dev server.",
      });
    } catch (e) {
      push({
        name: "2a. CSP allows Supabase (connect-src)",
        status: "warn",
        detail: e instanceof Error ? e.message : String(e),
      });
    }

    // 2b — the flip side: an origin NOT in connect-src must be refused. A pass here means the
    // policy is actually restrictive rather than permissive-by-accident, so an injected script
    // could not exfiltrate to an arbitrary host. Being blocked is the CORRECT outcome.
    try {
      await fetch("https://cloudflare.com/cdn-cgi/trace", {
        mode: "no-cors",
        cache: "no-store",
      });
      push({
        name: "2b. CSP blocks origins it should not allow",
        status: "warn",
        detail: "An unlisted external origin was REACHABLE — connect-src is not being enforced",
        hint: "The policy is not restricting anything. Check that the headers() block in next.config.mjs is applied to this route.",
      });
    } catch {
      push({
        name: "2b. CSP blocks origins it should not allow",
        status: "pass",
        detail: "Unlisted origin refused, as intended — connect-src is enforced",
      });
    }

    // 3 — does the auth API accept our key? Deliberately bogus credentials: a 400
    // "Invalid login credentials" is the ideal outcome, because it proves the key was
    // accepted and the request was processed.
    try {
      const { error } = await supabase.auth.signInWithPassword({
        email: "diagnostic-probe@invalid.test",
        password: "definitely-not-a-real-password",
      });
      if (!error) {
        push({
          name: "3. Supabase auth API responds",
          status: "warn",
          detail: "Unexpectedly signed in with probe credentials",
        });
      } else if (/failed to fetch|networkerror|load failed/i.test(error.message)) {
        push({
          name: "3. Supabase auth API responds",
          status: "fail",
          detail: error.message,
          hint: "Same network block as check 2.",
        });
      } else {
        push({
          name: "3. Supabase auth API responds",
          status: "pass",
          detail: `Rejected the probe as expected: "${error.message}" — auth is reachable and your key works.`,
        });
      }
    } catch (e) {
      push({
        name: "3. Supabase auth API responds",
        status: "fail",
        detail: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
      });
    }

    // 4 — is email confirmation on? Governs whether signup returns a session immediately.
    try {
      const res = await fetch(`${url}/auth/v1/settings`, {
        headers: { apikey: key },
        cache: "no-store",
      });
      const s = await res.json();
      push({
        name: "4. Email confirmation setting",
        status: s.mailer_autoconfirm ? "pass" : "warn",
        detail: s.mailer_autoconfirm
          ? "Auto-confirm ON — signup returns a session immediately"
          : "Auto-confirm OFF — every signup sends an email (free tier ≈3/hour, then 429)",
        hint: s.mailer_autoconfirm
          ? undefined
          : "Supabase dashboard → Authentication → Sign In / Providers → Email → uncheck 'Confirm email'.",
      });
    } catch (e) {
      push({
        name: "4. Email confirmation setting",
        status: "fail",
        detail: e instanceof Error ? e.message : String(e),
      });
    }

    // 5 — the Spring backend, which is a separate network path from Supabase.
    try {
      const res = await fetch(`${apiBase}/actuator/health`, { cache: "no-store" });
      const body = await res.json();
      push({
        name: "5. Browser can reach ExGPU backend",
        status: res.ok ? "pass" : "fail",
        detail: `HTTP ${res.status} · ${JSON.stringify(body)}`,
      });
    } catch (e) {
      push({
        name: "5. Browser can reach ExGPU backend",
        status: "fail",
        detail: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
        hint: "Is the backend running? cd exgpu && ./mvnw spring-boot:run",
      });
    }

    setRunning(false);
  }, []);

  useEffect(() => {
    void run();
  }, [run]);

  return (
    <div className="min-h-screen bg-surface-muted px-6 py-12">
      <div className="mx-auto max-w-2xl">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Connectivity diagnostics</h1>
        <p className="mt-1.5 text-sm text-ink-muted">
          Run from inside the browser, so it sees what the app sees.
        </p>

        <button
          onClick={() => {
            setResults([]);
            void run();
          }}
          disabled={running}
          className="mt-5 rounded-full border border-line-strong bg-surface px-5 py-2.5 text-sm font-medium text-ink transition hover:bg-surface-sunken disabled:opacity-50"
        >
          {running ? "Running…" : "Re-run"}
        </button>

        <div className="mt-6 space-y-3">
          {results.map((r) => (
            <div
              key={r.name}
              className={`rounded-xl border bg-surface p-5 ${
                r.status === "pass"
                  ? "border-positive/25 bg-positive/[0.06]"
                  : r.status === "fail"
                    ? "border-negative/25 bg-negative/[0.06]"
                    : "border-caution/25 bg-caution/[0.07]"
              }`}
            >
              <div className="flex items-start gap-2">
                <span className="text-sm">
                  {r.status === "pass" ? "✓" : r.status === "fail" ? "✗" : "!"}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold text-ink">{r.name}</p>
                  <p className="mt-1.5 break-words font-mono text-xs text-ink-soft">
                    {r.detail}
                  </p>
                  {r.hint && (
                    <p className="mt-2.5 text-xs leading-relaxed text-ink-muted">
                      → {r.hint}
                    </p>
                  )}
                </div>
              </div>
            </div>
          ))}
          {results.length === 0 && (
            <p className="text-sm text-ink-muted">Running checks…</p>
          )}
        </div>
      </div>
    </div>
  );
}
