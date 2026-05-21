"use client";

import Link from "next/link";

/**
 * The screen a crashed route falls back to.
 *
 * <p>Shared by every `error.tsx` boundary so a failure looks like part of the product rather
 * than a stack trace. Three things matter here and are easy to get wrong:
 *
 * <ul>
 *   <li><b>Retry actually retries.</b> `reset()` re-renders the segment without a full page
 *       load, so a transient fetch failure recovers without losing the session or scroll.</li>
 *   <li><b>The message is shown, not swallowed.</b> A boundary that says only "something went
 *       wrong" turns a five-second fix into a debugging session. In development the digest and
 *       message are printed; in production React strips the message from server errors and
 *       leaves the digest, which is the id you match against the server log.</li>
 *   <li><b>There is always a way out.</b> A dead end with no navigation is why a broken page
 *       feels like a broken app.</li>
 * </ul>
 */
export function ErrorState({
  error,
  reset,
  title = "This page hit an error",
  home = "/app",
}: {
  error: Error & { digest?: string };
  reset?: () => void;
  title?: string;
  home?: string;
}) {
  return (
    <div className="flex min-h-[60vh] items-center justify-center px-4 py-16">
      <div className="w-full max-w-lg text-center">
        <div
          aria-hidden
          className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl border border-negative/20 bg-negative/[0.07]"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="text-negative">
            <path
              d="M12 8v5m0 3.5h.01M10.3 3.9 2.6 17.4A2 2 0 0 0 4.3 20.4h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>

        <h1 className="mt-5 text-xl font-semibold tracking-tight text-ink">{title}</h1>
        <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-muted">
          Nothing was lost. Retrying usually clears it — the exchange itself is unaffected by a
          rendering failure here.
        </p>

        {(error.message || error.digest) && (
          <div className="mt-5 overflow-hidden rounded-xl border border-line bg-surface-sunken text-left">
            <p className="border-b border-line px-4 py-2 text-xs font-medium uppercase tracking-wider text-ink-muted">
              Details
            </p>
            <pre className="max-h-40 overflow-auto px-4 py-3 font-mono text-xs leading-relaxed text-ink-soft">
              {error.message || "(no message — production build)"}
              {error.digest ? `\n\ndigest: ${error.digest}` : ""}
            </pre>
          </div>
        )}

        <div className="mt-6 flex flex-wrap justify-center gap-2.5">
          {reset && (
            <button
              onClick={reset}
              className="inline-flex min-h-11 items-center rounded-full bg-brand-600 px-5 text-sm font-medium text-white shadow-brand transition hover:bg-brand-700"
            >
              Try again
            </button>
          )}
          <Link
            href={home}
            className="inline-flex min-h-11 items-center rounded-full border border-line-strong bg-surface px-5 text-sm font-medium text-ink transition hover:border-ink-faint hover:bg-surface-muted"
          >
            Back to safety
          </Link>
        </div>
      </div>
    </div>
  );
}
