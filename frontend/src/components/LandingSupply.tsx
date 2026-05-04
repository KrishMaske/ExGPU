"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { SupplyListing } from "@/lib/types";
import { fmtWindow, money } from "@/lib/format";

/**
 * Live inventory on the landing page, read from the public /market/supply endpoint.
 *
 * Client-side on purpose: it needs no account, and fetching in the browser keeps the
 * marketing page statically renderable while still showing current data.
 */
export function LandingSupply() {
  const [listings, setListings] = useState<SupplyListing[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;

    const load = () =>
      api
        .marketSupply()
        .then((l) => alive && setListings(l))
        .catch((e) => alive && setError(e.message));

    void load();

    // Polled rather than pushed. The realtime market feed rides the STOMP connection, which
    // requires an authenticated CONNECT — a logged-out visitor has no token and cannot
    // subscribe. Thirty seconds keeps a public shop window honest without hammering an
    // endpoint that anyone can call.
    const id = window.setInterval(load, 30_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  if (error) {
    return (
      <Panel>
        <p className="text-ink-soft">Couldn&apos;t reach the exchange right now.</p>
        <p className="mt-1.5 font-mono text-xs text-ink-faint">{error}</p>
      </Panel>
    );
  }

  if (listings === null) {
    return (
      <Panel>
        <p className="text-ink-muted">Loading inventory…</p>
      </Panel>
    );
  }

  if (listings.length === 0) {
    return (
      <Panel>
        <p className="text-lg font-medium text-ink">No capacity listed yet</p>
        <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-muted">
          The exchange is live but nobody has listed GPUs for a future window. If you have
          idle hardware, you&apos;d be the first provider.
        </p>
        <Link
          href="/signup"
          className="mt-6 inline-block rounded-full bg-brand-600 px-6 py-3 text-sm font-medium text-white shadow-brand transition hover:bg-brand-700"
        >
          List your GPUs
        </Link>
      </Panel>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-line bg-surface shadow-card">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-line text-left text-xs font-medium uppercase tracking-wider text-ink-muted">
              <th className="px-6 py-4">GPUs</th>
              <th className="px-6 py-4">Price / GPU-hr</th>
              <th className="px-6 py-4">Window</th>
              <th className="px-6 py-4 text-right">Full window / GPU</th>
              <th className="px-6 py-4" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {listings.slice(0, 8).map((l) => (
              <tr key={l.listingId} className="transition hover:bg-surface-muted">
                <td className="tnum px-6 py-5 text-lg font-semibold text-ink">
                  {l.availableGpus}
                </td>
                <td className="tnum px-6 py-5 font-medium text-brand-700">
                  {money(l.pricePerGpuHour)}
                </td>
                <td className="px-6 py-5 text-sm text-ink-soft">
                  {fmtWindow(l.windowStart, l.windowEnd)}
                  <span className="ml-2 text-ink-faint">· {l.windowHours}h</span>
                </td>
                <td className="tnum px-6 py-5 text-right font-medium text-ink">
                  {money(l.estimatedCostPerGpu)}
                </td>
                <td className="px-6 py-5 text-right">
                  <Link
                    href="/app/rent"
                    className="inline-block rounded-full border border-line-strong px-4 py-1.5 text-sm font-medium text-ink transition hover:border-brand-400 hover:bg-brand-50 hover:text-brand-700"
                  >
                    Rent
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {listings.length > 8 && (
        <div className="border-t border-line bg-surface-muted px-6 py-3 text-center text-sm text-ink-muted">
          + {listings.length - 8} more listings
        </div>
      )}
    </div>
  );
}

function Panel({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-line-strong bg-surface p-14 text-center">
      {children}
    </div>
  );
}
