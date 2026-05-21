"use client";

/**
 * Last resort: catches failures in the root layout itself, where no other boundary can run.
 *
 * <p>Because it replaces the root layout, it must render its own <html> and <body>, and it
 * cannot rely on the app's stylesheet having loaded — hence the inline styles. This is the
 * difference between a white screen and something that explains itself.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="en">
      <body
        style={{
          margin: 0,
          minHeight: "100vh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontFamily:
            'ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
          background: "#FAFAFC",
          color: "#0E0E13",
          padding: "2rem",
        }}
      >
        <div style={{ maxWidth: "32rem", textAlign: "center" }}>
          <h1 style={{ fontSize: "1.25rem", fontWeight: 600, margin: 0 }}>
            ExGPU failed to start
          </h1>
          <p style={{ marginTop: ".75rem", color: "#75758A", lineHeight: 1.6 }}>
            Something broke before the app could render. Your account and any active rentals
            are unaffected.
          </p>
          {error.digest && (
            <p
              style={{
                marginTop: "1rem",
                fontFamily: "ui-monospace, monospace",
                fontSize: ".75rem",
                color: "#A0A0B4",
              }}
            >
              digest: {error.digest}
            </p>
          )}
          <button
            onClick={reset}
            style={{
              marginTop: "1.5rem",
              minHeight: "2.75rem",
              padding: "0 1.25rem",
              borderRadius: "999px",
              border: "none",
              background: "#7C3AED",
              color: "#fff",
              fontSize: ".875rem",
              fontWeight: 500,
              cursor: "pointer",
            }}
          >
            Reload
          </button>
        </div>
      </body>
    </html>
  );
}
