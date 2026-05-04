import "./globals.css";
import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import { AuthProvider } from "@/lib/auth-context";
import { ThemeProvider, themeScript } from "@/lib/theme";

/**
 * Inter, self-hosted by next/font at build time.
 *
 * <p>The app previously rode on the `system-ui` stack, which means a different typeface per
 * operating system — Segoe UI on Windows, SF on macOS — so the product had no consistent voice
 * and column widths shifted between machines. Inter is the usual choice for dense numeric UI:
 * it has genuine tabular figures (which the `.tnum` utility switches on for every price and
 * balance) and holds up at the small sizes the tables and cards use.
 *
 * <p>`display: "swap"` renders fallback text immediately rather than blocking on the font, and
 * next/font self-hosts the files, so there is no request to Google at runtime — which also
 * matters because the CSP in next.config.mjs allows no third-party origins.
 */
const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-sans",
});

const configuredSiteUrl = process.env.NEXT_PUBLIC_SITE_URL;
const socialImageUrl = configuredSiteUrl
  ? new URL("/og.png", configuredSiteUrl).toString()
  : undefined;

export const metadata: Metadata = {
  metadataBase: configuredSiteUrl ? new URL(configuredSiteUrl) : undefined,
  title: {
    default: "ExGPU — GPU compute for the window you need",
    template: "%s | ExGPU",
  },
  description:
    "Browse live GPU capacity or request an exact quantity, price ceiling, and compute window.",
  icons: { icon: "/exgpu_logo.png" },
  openGraph: {
    title: "ExGPU — GPU compute for the window you need",
    description:
      "Match live capacity to your quantity, budget, and exact time window.",
    type: "website",
    images: socialImageUrl
      ? [{ url: socialImageUrl, width: 1731, height: 909, alt: "ExGPU marketplace" }]
      : undefined,
  },
  twitter: {
    card: "summary_large_image",
    title: "ExGPU — GPU compute for the window you need",
    description:
      "Match live capacity to your quantity, budget, and exact time window.",
    images: socialImageUrl ? [socialImageUrl] : undefined,
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  // Two entries so the browser chrome (address bar on mobile) matches the active theme
  // instead of staying white behind a dark page.
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#FFFFFF" },
    { media: "(prefers-color-scheme: dark)", color: "#0C0C10" },
  ],
  colorScheme: "light dark",
};

/**
 * Root layout holds only what every route needs: styles and the auth session.
 *
 * The signed-in chrome (sidebar, live event feed, toasts) lives in the /app layout instead,
 * so marketing and auth pages render without a dashboard frame around them.
 */
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    // suppressHydrationWarning on <html> covers the data-theme attribute that themeScript
    // writes before React hydrates — the server cannot know the viewer's theme, so that
    // attribute legitimately differs and React must be told not to treat it as a fault.
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <head>
        {/* Must run before first paint, hence a raw inline script rather than an effect. */}
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      {/*
        suppressHydrationWarning is for browser extensions, not our own markup. Grammarly and
        friends inject attributes (data-gr-ext-installed, data-new-gr-c-s-check-loaded) onto
        <body> before React hydrates, which React reports as a mismatch we can neither cause
        nor fix. This flag is one level deep — it silences attribute diffs on <body> itself and
        nothing below it, so a genuine mismatch inside the tree still surfaces.
      */}
      <body suppressHydrationWarning>
        <a href="#main" className="skip-link">
          Skip to content
        </a>
        <ThemeProvider>
          <AuthProvider>{children}</AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
