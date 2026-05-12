"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ErrorState";

/**
 * Boundary for the signed-in area.
 *
 * <p>Separate from the public one so a crash inside /app keeps the dashboard's own layout —
 * the header, balance and navigation stay mounted, and the user lands somewhere they can
 * still work from rather than being thrown out to the marketing site.
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("App route error:", error);
  }, [error]);

  return <ErrorState error={error} reset={reset} home="/app" />;
}
