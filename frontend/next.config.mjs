/** @type {import('next').NextConfig} */

const IS_DEV = process.env.NODE_ENV !== "production";
const API_ORIGIN = process.env.NEXT_PUBLIC_API_BASE ?? (IS_DEV ? "http://localhost:8080" : "");
const WS_ORIGIN = (process.env.NEXT_PUBLIC_WS_URL ?? (IS_DEV ? "ws://localhost:8080/ws" : "")).replace(/\/ws$/, "");

const SUPABASE_ORIGIN = (() => {
  const raw = process.env.NEXT_PUBLIC_SUPABASE_URL;
  if (!raw) return "";
  try {
    const { origin, host } = new URL(raw);
    return `${origin} wss://${host}`;
  } catch {
    return "";
  }
})();

const scriptSrc = ["'self'", "'unsafe-inline'", ...(IS_DEV ? ["'unsafe-eval'"] : [])].join(" ");
const connectSrc = ["'self'", API_ORIGIN, WS_ORIGIN, ...(IS_DEV ? ["ws://localhost:3001"] : []), SUPABASE_ORIGIN].filter(Boolean).join(" ");
const csp = [
  "default-src 'self'",
  `script-src ${scriptSrc}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  `connect-src ${connectSrc}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

if (!IS_DEV) {
  const missing = [
    !process.env.NEXT_PUBLIC_SITE_URL && "NEXT_PUBLIC_SITE_URL",
    !process.env.NEXT_PUBLIC_API_BASE && "NEXT_PUBLIC_API_BASE",
    !process.env.NEXT_PUBLIC_WS_URL && "NEXT_PUBLIC_WS_URL",
    !process.env.NEXT_PUBLIC_SUPABASE_URL && "NEXT_PUBLIC_SUPABASE_URL",
    !process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY && "NEXT_PUBLIC_SUPABASE_ANON_KEY",
  ].filter(Boolean);
  if (missing.length) {
    throw new Error(`[config] Missing required production variables: ${missing.join(", ")}`);
  }
}

const securityHeaders = [
  { key: "Content-Security-Policy", value: csp },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), browsing-topics=()" },
];

const nextConfig = {
  // `next build` and `next dev` both write to .next. Running a build while a dev server is
  // up leaves production manifests interleaved with dev output, and the dev router then
  // serves stale routes — that is what made /login and /diagnostics 404 while / still worked.
  // Setting NEXT_DIST_DIR gives a build (or a second dev server) its own directory:
  //   NEXT_DIST_DIR=.next-build npx next build
  distDir: process.env.NEXT_DIST_DIR || ".next",
  reactStrictMode: true,
  agentRules: false,
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
