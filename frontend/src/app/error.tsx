"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ErrorState";

/**
 * Boundary for the public routes. Catches anything thrown below the root layout so a failure
 * renders a real page instead of Next's bare error screen.
 */
export default function PublicError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // The boundary swallows the throw, so without this the only record of what broke is a
    // digest with no matching console entry.
    console.error("Route error:", error);
  }, [error]);

  return <ErrorState error={error} reset={reset} home="/" />;
}
