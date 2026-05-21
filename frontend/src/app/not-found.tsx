import Link from "next/link";

export const metadata = { title: "Page not found" };

/** Replaces Next's default 404, which is unstyled and offers no way back. */
export default function NotFound() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted px-4">
      <div className="w-full max-w-md text-center">
        <p className="tnum text-5xl font-semibold tracking-tight text-brand-600">404</p>
        <h1 className="mt-4 text-xl font-semibold tracking-tight text-ink">
          We couldn&apos;t find that page
        </h1>
        <p className="mx-auto mt-2 max-w-sm text-sm leading-relaxed text-ink-muted">
          The link may be out of date, or the listing it pointed at has since been filled.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2.5">
          <Link
            href="/app/rent"
            className="inline-flex min-h-11 items-center rounded-full bg-brand-600 px-5 text-sm font-medium text-white shadow-brand transition hover:bg-brand-700"
          >
            Browse GPUs
          </Link>
          <Link
            href="/"
            className="inline-flex min-h-11 items-center rounded-full border border-line-strong bg-surface px-5 text-sm font-medium text-ink transition hover:border-ink-faint hover:bg-surface-muted"
          >
            Home
          </Link>
        </div>
      </div>
    </div>
  );
}
