"use client";

import { EventsProvider } from "@/lib/events-context";
import { AppShell } from "@/components/AppShell";
import { useAuth } from "@/lib/auth-context";

/**
 * Layout for the signed-in area.
 *
 * <p>The middleware already redirects logged-out visitors, but it runs on the server and
 * cannot prevent a brief client render before the session resolves. Holding here until
 * `loading` clears avoids flashing an empty dashboard.
 *
 * <p>EventsProvider is mounted here rather than at the root so the WebSocket only opens for
 * authenticated pages — the landing and auth screens have nothing to subscribe to.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { loading, user } = useAuth();

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-muted">
        <p className="text-sm text-ink-muted">Loading…</p>
      </div>
    );
  }

  // Middleware handles the redirect; this is the fallback for the brief window where
  // the client knows there is no session but navigation has not happened yet.
  if (!user) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-muted">
        <p className="text-sm text-ink-muted">Redirecting to sign in…</p>
      </div>
    );
  }

  return (
    <EventsProvider>
      <AppShell>{children}</AppShell>
    </EventsProvider>
  );
}
