export function safeAppPath(raw: string | null | undefined): string {
  if (!raw) return "/app";
  const value = raw.trim();
  if (!value.startsWith("/") || value.startsWith("//") || value.includes("\\") || value.length > 2048) {
    return "/app";
  }
  try {
    const parsed = new URL(value, "https://exgpu.invalid");
    const isAppPath = parsed.pathname === "/app" || parsed.pathname.startsWith("/app/");
    if (parsed.origin !== "https://exgpu.invalid" || !isAppPath) return "/app";
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return "/app";
  }
}

export function authHref(route: "/login" | "/signup", destination: string): string {
  return `${route}?${new URLSearchParams({ next: safeAppPath(destination) })}`;
}
