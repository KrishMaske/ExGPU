import { createServerClient } from "@supabase/ssr";
import { NextResponse, type NextRequest } from "next/server";
import { safeAppPath } from "@/lib/navigation";

/**
 * Gates the signed-in area and keeps the Supabase session cookie fresh.
 *
 * <p>This is a routing convenience, not the security boundary. It stops a logged-out visitor
 * landing on an empty dashboard, but it protects no data: every piece of data lives behind
 * the Spring API, which independently verifies the JWT on each request. Someone bypassing
 * this middleware would reach a page whose API calls all return 401.
 *
 * <p>It also refreshes the auth cookie on each navigation, so a long-lived tab does not
 * silently drift into an expired session.
 */
export async function proxy(request: NextRequest) {
  let response = NextResponse.next({ request });

  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

  // Without credentials there is no session to check; let the page render its own
  // "auth not configured" state rather than redirect-looping.
  if (!url || !key) return response;

  const supabase = createServerClient(url, key, {
    cookies: {
      getAll() {
        return request.cookies.getAll();
      },
      setAll(cookiesToSet) {
        cookiesToSet.forEach(({ name, value }) => request.cookies.set(name, value));
        response = NextResponse.next({ request });
        cookiesToSet.forEach(({ name, value, options }) =>
          response.cookies.set(name, value, options)
        );
      },
    },
  });

  // getUser() revalidates against Supabase rather than trusting the cookie's contents.
  const {
    data: { user },
  } = await supabase.auth.getUser();

  const path = request.nextUrl.pathname;
  const isAppRoute = path.startsWith("/app");
  // /diagnostics is excluded on purpose: it exists to debug a broken auth path, so it must
  // stay reachable when signing in is exactly what does not work.
  const isAuthRoute = path === "/login" || path === "/signup";

  if (isAppRoute && !user) {
    const redirect = new URL("/login", request.url);
    // Remember where they were headed so sign-in can return them there.
    redirect.searchParams.set("next", `${request.nextUrl.pathname}${request.nextUrl.search}`);
    return NextResponse.redirect(redirect);
  }

  if (isAuthRoute && user) {
    return NextResponse.redirect(
      new URL(safeAppPath(request.nextUrl.searchParams.get("next")), request.url)
    );
  }

  return response;
}

export const config = {
  // Skip static assets and images — running auth on every chunk request is pure overhead.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)"],
};
